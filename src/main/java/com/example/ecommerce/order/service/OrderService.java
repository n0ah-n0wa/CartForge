package com.example.ecommerce.order.service;

import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.entity.CartItem;
import com.example.ecommerce.cart.service.CartCheckoutPort;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.PageRequests;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.dto.CheckoutCommand;
import com.example.ecommerce.order.dto.CheckoutResult;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.entity.CheckoutIdempotencyKey;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.entity.OrderItem;
import com.example.ecommerce.order.mapper.OrderMapper;
import com.example.ecommerce.order.repository.CheckoutIdempotencyKeyRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places orders from the authenticated customer's cart in one database
 * transaction: validate cart and catalog, snapshot prices, decrement stock,
 * persist the order, then clear the cart. Any failure rolls the whole unit back.
 *
 * <p>When {@code Idempotency-Key} is present, a PostgreSQL row for that user and
 * key is written only if checkout commits, so retries cannot create a second
 * order and failed attempts do not reserve a successful replay.
 */
@Service
@Transactional
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final CartCheckoutPort cartCheckoutPort;
    private final OrderRepository orderRepository;
    private final CheckoutIdempotencyKeyRepository checkoutIdempotencyKeyRepository;
    private final InventoryService inventoryService;
    private final OrderNumberGenerator orderNumberGenerator;
    private final OrderMapper orderMapper;
    private final ApplicationProperties properties;

    public OrderService(
            CurrentUserProvider currentUserProvider,
            UserRepository userRepository,
            CartCheckoutPort cartCheckoutPort,
            OrderRepository orderRepository,
            CheckoutIdempotencyKeyRepository checkoutIdempotencyKeyRepository,
            InventoryService inventoryService,
            OrderNumberGenerator orderNumberGenerator,
            OrderMapper orderMapper,
            ApplicationProperties properties) {
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
        this.cartCheckoutPort = cartCheckoutPort;
        this.orderRepository = orderRepository;
        this.checkoutIdempotencyKeyRepository = checkoutIdempotencyKeyRepository;
        this.inventoryService = inventoryService;
        this.orderNumberGenerator = orderNumberGenerator;
        this.orderMapper = orderMapper;
        this.properties = properties;
    }

    /**
     * Returns a paginated order history for the authenticated customer. Lines are
     * omitted from each entry; use {@link #getOrder(Long)} for full snapshots.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listOrders(Integer page, Integer size, List<String> sortParams) {
        long userId = currentUserProvider.requireUserId();
        Sort resolvedSort = OrderSortSupport.ALLOWED.resolve(sortParams);
        Pageable pageable = PageRequests.of(
                page,
                size,
                properties.pagination().defaultPageSize(),
                properties.pagination().maxPageSize(),
                resolvedSort);
        Page<Order> result = orderRepository.findByUserId(userId, pageable);
        return new PageResponse<>(
                result.getContent().stream().map(orderMapper::toSummaryResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Returns one order with snapshot lines for the authenticated customer.
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        long userId = currentUserProvider.requireUserId();
        Order order = orderRepository.findWithItemsByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return orderMapper.toResponse(order);
    }

    /**
     * Cancels a cancellable order for the authenticated customer, restores
     * inventory for every line, and persists the status change in one transaction.
     * The order row is locked so concurrent cancellation attempts cannot restore
     * stock twice.
     */
    public OrderResponse cancelOrder(Long orderId) {
        long userId = currentUserProvider.requireUserId();
        Order order = orderRepository.findWithItemsByIdAndUserIdForUpdate(orderId, userId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        order.cancel();

        for (OrderItem line : sortedLines(order)) {
            inventoryService.restoreStock(line.getProduct().getId(), line.getQuantity());
        }

        Order saved = orderRepository.saveAndFlush(order);
        return orderMapper.toResponse(saved);
    }

    /**
     * Executes checkout for the authenticated customer without an idempotency key.
     */
    public OrderResponse checkout(CheckoutCommand command) {
        return checkout(command, null).order();
    }

    /**
     * Executes checkout, optionally keyed by {@code Idempotency-Key}.
     *
     * @return the order and whether it was replayed from a prior successful key
     */
    public CheckoutResult checkout(CheckoutCommand command, String idempotencyKeyHeader) {
        long userId = currentUserProvider.requireUserId();
        User customer = userRepository.findById(userId)
                .orElseThrow(() -> new OrderOwnerNotFoundException(userId));

        var parsedKey = IdempotencyKeys.parse(idempotencyKeyHeader);
        if (parsedKey.isPresent()) {
            String key = parsedKey.get();
            String fingerprint = IdempotencyKeys.fingerprint(command);
            checkoutIdempotencyKeyRepository.lockByUserIdAndKey(userId, key);
            Optional<CheckoutIdempotencyKey> existing =
                    checkoutIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(userId, key);
            if (existing.isPresent()) {
                CheckoutIdempotencyKey record = existing.get();
                if (!fingerprint.equals(record.getRequestFingerprint())) {
                    throw new IdempotencyKeyConflictException();
                }
                Order replayed = orderRepository
                        .findWithItemsByIdAndUserId(record.getOrder().getId(), userId)
                        .orElseThrow(() -> new OrderNotFoundException(record.getOrder().getId()));
                log.info(
                        "event=checkout_succeeded userId={} orderId={} orderNumber={} replayed=true",
                        userId,
                        replayed.getId(),
                        replayed.getOrderNumber());
                return new CheckoutResult(orderMapper.toResponse(replayed), true);
            }
            Order created = placeOrder(customer, command);
            checkoutIdempotencyKeyRepository.saveAndFlush(
                    CheckoutIdempotencyKey.completed(customer, key, fingerprint, created));
            log.info(
                    "event=checkout_succeeded userId={} orderId={} orderNumber={} replayed=false",
                    userId,
                    created.getId(),
                    created.getOrderNumber());
            return new CheckoutResult(orderMapper.toResponse(created), false);
        }

        Order created = placeOrder(customer, command);
        log.info(
                "event=checkout_succeeded userId={} orderId={} orderNumber={} replayed=false",
                userId,
                created.getId(),
                created.getOrderNumber());
        return new CheckoutResult(orderMapper.toResponse(created), false);
    }

    private Order placeOrder(User customer, CheckoutCommand command) {
        long userId = customer.getId();
        Cart cart = cartCheckoutPort.requireNonEmptyCartForCheckout(userId);

        for (CartItem line : sortedLines(cart)) {
            Product product = line.getProduct();
            if (!product.isActive()) {
                throw new InactiveProductForCheckoutException(product.getId());
            }
            inventoryService.validateAvailability(product.getId(), line.getQuantity());
        }

        Order order = Order.place(
                orderNumberGenerator.nextOrderNumber(),
                customer,
                command.shippingAddress(),
                PersistenceConventions.DEFAULT_CURRENCY);

        for (CartItem line : sortedLines(cart)) {
            // Lock + refresh before snapshot so concurrent catalog price changes
            // cannot charge a stale cart-graph copy while debiting the locked row.
            Product locked = inventoryService.lockForCheckout(line.getProduct().getId());
            if (!locked.isActive()) {
                throw new InactiveProductForCheckoutException(locked.getId());
            }
            order.addItem(locked, line.getQuantity());
            inventoryService.decreaseStock(locked.getId(), line.getQuantity());
        }

        Order saved = orderRepository.saveAndFlush(order);

        cartCheckoutPort.clearAfterCheckout(userId);

        return saved;
    }

    private static List<CartItem> sortedLines(Cart cart) {
        return cart.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();
    }

    private static List<OrderItem> sortedLines(Order order) {
        return order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                .toList();
    }
}
