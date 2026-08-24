package com.example.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.InvalidSortException;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.inventory.dto.StockLevel;
import com.example.ecommerce.inventory.service.InsufficientStockException;
import com.example.ecommerce.inventory.service.InventoryConflictException;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.OrderStatusTransitionException;
import com.example.ecommerce.order.dto.CheckoutCommand;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.mapper.OrderMapper;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final long USER_ID = 11L;
    private static final long PRODUCT_ID = 42L;
    private static final Category CATEGORY = Category.create("Books", "books", null);
    private static final CheckoutCommand CHECKOUT =
            new CheckoutCommand("1 Main Street, Springfield");

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryService inventoryService;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    private final OrderMapper orderMapper = new OrderMapper();

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties(
                new ApplicationProperties.Jwt("test-only-jwt-secret-not-for-production-use", 3_600_000L),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100));
        orderService = new OrderService(
                currentUserProvider,
                userRepository,
                cartRepository,
                orderRepository,
                inventoryService,
                orderNumberGenerator,
                orderMapper,
                properties);
    }

    @Test
    void checkoutCreatesOrderDecrementsStockAndClearsCart() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 10, true);
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(keyboard, 2);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer));
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(orderNumberGenerator.nextOrderNumber()).thenReturn("ORD-2026-000001");
        when(inventoryService.decreaseStock(PRODUCT_ID, 2))
                .thenReturn(new StockLevel(PRODUCT_ID, 8, 1L));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 100L);
            return order;
        });
        when(cartRepository.save(cart)).thenReturn(cart);

        OrderResponse response = orderService.checkout(CHECKOUT);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.orderNumber()).isEqualTo("ORD-2026-000001");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo("99.00");
        assertThat(response.currency()).isEqualTo(CurrencyCode.EUR);
        assertThat(response.shippingAddress()).isEqualTo("1 Main Street, Springfield");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productId()).isEqualTo(PRODUCT_ID);
        assertThat(response.items().getFirst().productName()).isEqualTo("Keyboard");
        assertThat(response.items().getFirst().sku()).isEqualTo("KB-001");
        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("49.50");
        assertThat(response.items().getFirst().quantity()).isEqualTo(2);
        assertThat(response.items().getFirst().lineTotal()).isEqualByComparingTo("99.00");

        assertThat(cart.isEmpty()).isTrue();
        verify(inventoryService).validateAvailability(PRODUCT_ID, 2);
        verify(inventoryService).decreaseStock(PRODUCT_ID, 2);
        verify(cartRepository).save(cart);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getItems()).hasSize(1);
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualByComparingTo("99.00");
    }

    @Test
    void checkoutRejectsWhenCustomerHasNoCart() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.checkout(CHECKOUT))
                .isInstanceOf(EmptyCartException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(inventoryService, never()).decreaseStock(anyLong(), anyInt());
    }

    @Test
    void checkoutRejectsEmptyCart() {
        User customer = user(USER_ID);
        Cart empty = Cart.forUser(customer);
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer));
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(empty));

        assertThatThrownBy(() -> orderService.checkout(CHECKOUT))
                .isInstanceOf(EmptyCartException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(inventoryService, never()).decreaseStock(anyLong(), anyInt());
    }

    @Test
    void checkoutRejectsInactiveProductBeforeMutatingInventoryOrOrder() {
        User customer = user(USER_ID);
        Product inactive = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 10, false);
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(inactive, 1);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer));
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> orderService.checkout(CHECKOUT))
                .isInstanceOf(InactiveProductForCheckoutException.class)
                .satisfies(ex -> assertThat(((InactiveProductForCheckoutException) ex).getProductId())
                        .isEqualTo(PRODUCT_ID));

        verify(inventoryService, never()).validateAvailability(anyLong(), anyInt());
        verify(inventoryService, never()).decreaseStock(anyLong(), anyInt());
        verify(orderRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).save(any());
        assertThat(cart.isEmpty()).isFalse();
    }

    @Test
    void checkoutRejectsInsufficientStockWithoutCreatingAnOrder() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 1, true);
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(keyboard, 2);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer));
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        doThrow(new InsufficientStockException(PRODUCT_ID, 1, 2))
                .when(inventoryService)
                .validateAvailability(PRODUCT_ID, 2);

        assertThatThrownBy(() -> orderService.checkout(CHECKOUT))
                .isInstanceOf(InsufficientStockException.class);

        verify(inventoryService, never()).decreaseStock(anyLong(), anyInt());
        verify(orderRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).save(any());
        assertThat(cart.isEmpty()).isFalse();
    }

    @Test
    void checkoutSurfacesInventoryConflictsFromOptimisticLocking() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 5, true);
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(keyboard, 1);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer));
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(orderNumberGenerator.nextOrderNumber()).thenReturn("ORD-2026-000002");
        when(inventoryService.decreaseStock(eq(PRODUCT_ID), eq(1)))
                .thenThrow(new InventoryConflictException(PRODUCT_ID));

        assertThatThrownBy(() -> orderService.checkout(CHECKOUT))
                .isInstanceOf(InventoryConflictException.class);

        verify(orderRepository, never()).saveAndFlush(any());
        verify(cartRepository, never()).save(any());
    }

    @Test
    void checkoutRejectsUnknownAuthenticatedOwner() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.checkout(CHECKOUT))
                .isInstanceOf(OrderOwnerNotFoundException.class);

        verify(cartRepository, never()).findWithItemsByUserIdForUpdate(anyLong());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void checkoutSnapshotsCatalogFieldsEvenIfProductWouldLaterChange() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 10, true);
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(keyboard, 1);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer));
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(orderNumberGenerator.nextOrderNumber()).thenReturn("ORD-2026-000003");
        when(inventoryService.decreaseStock(PRODUCT_ID, 1))
                .thenReturn(new StockLevel(PRODUCT_ID, 9, 1L));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartRepository.save(cart)).thenReturn(cart);

        OrderResponse response = orderService.checkout(CHECKOUT);

        keyboard.rename("Keyboard Pro", "keyboard-pro");
        keyboard.changePrice(new BigDecimal("99.99"), CurrencyCode.EUR);

        assertThat(response.items().getFirst().productName()).isEqualTo("Keyboard");
        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("49.50");
        assertThat(response.totalAmount()).isEqualByComparingTo("49.50");
    }

    @Test
    void listOrdersReturnsPagedSummariesForTheAuthenticatedCustomer() {
        User customer = user(USER_ID);
        Order order = Order.place("ORD-2026-000010", customer, "1 Main Street", CurrencyCode.EUR);
        ReflectionTestUtils.setField(order, "id", 500L);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(orderRepository.findByUserId(eq(USER_ID), pageableCaptor.capture()))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    return new PageImpl<>(List.of(order), pageable, 1);
                });

        PageResponse<OrderSummaryResponse> page = orderService.listOrders(0, 10, List.of());

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().id()).isEqualTo(500L);
        assertThat(page.content().getFirst().orderNumber()).isEqualTo("ORD-2026-000010");
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listOrdersRejectsUnsupportedSortFields() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);

        assertThatThrownBy(() -> orderService.listOrders(0, 10, List.of("shippingAddress,asc")))
                .isInstanceOf(InvalidSortException.class);

        verify(orderRepository, never()).findByUserId(anyLong(), any());
    }

    @Test
    void getOrderReturnsSnapshotLinesForTheAuthenticatedCustomer() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 10, true);
        Order order = Order.place("ORD-2026-000011", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 1);
        ReflectionTestUtils.setField(order, "id", 501L);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(orderRepository.findWithItemsByIdAndUserId(501L, USER_ID)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrder(501L);

        assertThat(response.id()).isEqualTo(501L);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productName()).isEqualTo("Keyboard");
        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("49.50");
    }

    @Test
    void getOrderRejectsOrdersThatBelongToAnotherCustomer() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(orderRepository.findWithItemsByIdAndUserId(999L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(999L)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void cancelOrderTransitionsToCancelledAndRestoresInventory() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 10, true);
        Order order = Order.place("ORD-2026-000020", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 2);
        ReflectionTestUtils.setField(order, "id", 600L);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(orderRepository.findWithItemsByIdAndUserIdForUpdate(600L, USER_ID)).thenReturn(Optional.of(order));
        when(inventoryService.restoreStock(PRODUCT_ID, 2)).thenReturn(new StockLevel(PRODUCT_ID, 12, 1L));
        when(orderRepository.saveAndFlush(order)).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.cancelOrder(600L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productName()).isEqualTo("Keyboard");
        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("49.50");
        verify(inventoryService).restoreStock(PRODUCT_ID, 2);
        verify(orderRepository).saveAndFlush(order);
    }

    @Test
    void cancelOrderRejectsNonCancellableStatuses() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 10, true);
        Order order = Order.place("ORD-2026-000021", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 1);
        order.transitionTo(OrderStatus.CONFIRMED);
        order.transitionTo(OrderStatus.PROCESSING);
        ReflectionTestUtils.setField(order, "id", 601L);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(orderRepository.findWithItemsByIdAndUserIdForUpdate(601L, USER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(601L))
                .isInstanceOf(OrderStatusTransitionException.class);

        verify(inventoryService, never()).restoreStock(anyLong(), anyInt());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelOrderRejectsUnknownOrForeignOrders() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(orderRepository.findWithItemsByIdAndUserIdForUpdate(602L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder(602L)).isInstanceOf(OrderNotFoundException.class);

        verify(inventoryService, never()).restoreStock(anyLong(), anyInt());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelOrderDoesNotPersistWhenInventoryRestoreFails() {
        User customer = user(USER_ID);
        Product keyboard = product(PRODUCT_ID, "KB-001", "Keyboard", "keyboard", "49.50", 10, true);
        Order order = Order.place("ORD-2026-000022", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 1);
        ReflectionTestUtils.setField(order, "id", 603L);

        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(orderRepository.findWithItemsByIdAndUserIdForUpdate(603L, USER_ID)).thenReturn(Optional.of(order));
        doThrow(new InventoryConflictException(PRODUCT_ID)).when(inventoryService).restoreStock(PRODUCT_ID, 1);

        assertThatThrownBy(() -> orderService.cancelOrder(603L)).isInstanceOf(InventoryConflictException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    private static User user(long id) {
        User user = User.registerCustomer(
                "customer@example.com", "test-only-password-hash", "Ada", "Lovelace");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Product product(
            long id, String sku, String name, String slug, String price, int stock, boolean active) {
        Product product = Product.create(
                sku, name, slug, null, new BigDecimal(price), null, stock, CATEGORY);
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "version", 0L);
        if (!active) {
            product.deactivate();
        }
        return product;
    }
}
