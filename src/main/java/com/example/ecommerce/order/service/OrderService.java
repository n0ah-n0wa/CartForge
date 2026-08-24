package com.example.ecommerce.order.service;

import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.entity.CartItem;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.PageRequests;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.dto.CheckoutCommand;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.entity.OrderItem;
import com.example.ecommerce.order.mapper.OrderMapper;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.util.Comparator;
import java.util.List;
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
 * <p>Idempotency is intentionally out of scope for this step.
 */
@Service
@Transactional
public class OrderService {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final OrderNumberGenerator orderNumberGenerator;
    private final OrderMapper orderMapper;
    private final ApplicationProperties properties;

    public OrderService(
            CurrentUserProvider currentUserProvider,
            UserRepository userRepository,
            CartRepository cartRepository,
            OrderRepository orderRepository,
            InventoryService inventoryService,
            OrderNumberGenerator orderNumberGenerator,
            OrderMapper orderMapper,
            ApplicationProperties properties) {
        this.currentUserProvider = currentUserProvider;
        this.userRepository = userRepository;
        this.cartRepository = cartRepository;
        this.orderRepository = orderRepository;
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
     * Executes checkout for the authenticated customer.
     *
     * @return the persisted order including snapshot lines and total
     */
    public OrderResponse checkout(CheckoutCommand command) {
        long userId = currentUserProvider.requireUserId();
        User customer = userRepository.findById(userId)
                .orElseThrow(() -> new OrderOwnerNotFoundException(userId));

        Cart cart = cartRepository.findWithItemsByUserIdForUpdate(userId)
                .orElseThrow(EmptyCartException::new);
        if (cart.isEmpty()) {
            throw new EmptyCartException();
        }

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
            Product product = line.getProduct();
            // Snapshot current catalog price/name/sku onto the line, then debit stock.
            order.addItem(product, line.getQuantity());
            inventoryService.decreaseStock(product.getId(), line.getQuantity());
        }

        Order saved = orderRepository.saveAndFlush(order);

        cart.clear();
        cartRepository.save(cart);

        return orderMapper.toResponse(saved);
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
