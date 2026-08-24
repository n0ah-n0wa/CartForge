package com.example.ecommerce.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.InvalidSortException;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.inventory.dto.StockLevel;
import com.example.ecommerce.inventory.service.InventoryConflictException;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.OrderStatusTransitionException;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.dto.UpdateOrderStatusCommand;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.mapper.OrderMapper;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
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
class AdminOrderServiceTest {

    private static final long ORDER_ID = 700L;
    private static final long PRODUCT_ID = 42L;
    private static final Category CATEGORY = Category.create("Books", "books", null);

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryService inventoryService;

    private final OrderMapper orderMapper = new OrderMapper();

    private AdminOrderService adminOrderService;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties(
                new ApplicationProperties.Jwt("test-only-jwt-secret-not-for-production-use", 3_600_000L),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100),
                ApplicationProperties.RateLimit.defaults());
        adminOrderService = new AdminOrderService(orderRepository, inventoryService, orderMapper, properties);
    }

    @Test
    void listOrdersReturnsPagedSummariesAcrossCustomers() {
        Order order = pendingOrder(2);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderRepository.findAll(pageableCaptor.capture())).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            return new PageImpl<>(List.of(order), pageable, 1);
        });

        PageResponse<OrderSummaryResponse> page = adminOrderService.listOrders(0, 10, List.of(), null);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().orderNumber()).isEqualTo("ORD-2026-000700");
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        verify(orderRepository, never()).findByStatus(any(), any());
    }

    @Test
    void listOrdersFiltersByStatus() {
        Order confirmed = pendingOrder(1);
        confirmed.transitionTo(OrderStatus.CONFIRMED);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderRepository.findByStatus(eq(OrderStatus.CONFIRMED), pageableCaptor.capture()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(confirmed), invocation.getArgument(1), 1));

        PageResponse<OrderSummaryResponse> page =
                adminOrderService.listOrders(0, 5, List.of(), OrderStatus.CONFIRMED);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listOrdersRejectsUnsupportedSortFields() {
        assertThatThrownBy(() -> adminOrderService.listOrders(0, 10, List.of("shippingAddress,asc"), null))
                .isInstanceOf(InvalidSortException.class);

        verify(orderRepository, never()).findAll(any(Pageable.class));
        verify(orderRepository, never()).findByStatus(any(), any());
    }

    @Test
    void getOrderReturnsSnapshotLinesRegardlessOfOwner() {
        Order order = pendingOrder(1);
        when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order));

        OrderResponse response = adminOrderService.getOrder(ORDER_ID);

        assertThat(response.id()).isEqualTo(ORDER_ID);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().productName()).isEqualTo("Keyboard");
        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("49.50");
    }

    @Test
    void getOrderRejectsUnknownIds() {
        when(orderRepository.findWithItemsById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminOrderService.getOrder(ORDER_ID)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void updateStatusAppliesAValidForwardTransitionWithoutRestoringStock() {
        Order order = pendingOrder(2);
        when(orderRepository.findWithItemsByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(order)).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = adminOrderService.updateStatus(
                ORDER_ID, new UpdateOrderStatusCommand(OrderStatus.CONFIRMED));

        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        verify(inventoryService, never()).restoreStock(anyLong(), anyInt());
        verify(orderRepository).saveAndFlush(order);
    }

    @Test
    void updateStatusToCancelledRestoresInventory() {
        Order order = pendingOrder(2);
        when(orderRepository.findWithItemsByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(inventoryService.restoreStock(PRODUCT_ID, 2)).thenReturn(new StockLevel(PRODUCT_ID, 12, 1L));
        when(orderRepository.saveAndFlush(order)).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = adminOrderService.updateStatus(
                ORDER_ID, new UpdateOrderStatusCommand(OrderStatus.CANCELLED));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.items().getFirst().productName()).isEqualTo("Keyboard");
        verify(inventoryService).restoreStock(PRODUCT_ID, 2);
    }

    @Test
    void updateStatusToCancelledDoesNotPersistWhenInventoryRestoreFails() {
        Order order = pendingOrder(1);
        when(orderRepository.findWithItemsByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        doThrow(new InventoryConflictException(PRODUCT_ID)).when(inventoryService).restoreStock(PRODUCT_ID, 1);

        assertThatThrownBy(() -> adminOrderService.updateStatus(
                        ORDER_ID, new UpdateOrderStatusCommand(OrderStatus.CANCELLED)))
                .isInstanceOf(InventoryConflictException.class);

        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatusToCancelledRestoresEveryLineInProductIdOrder() {
        Order order = twoLineOrder();
        when(orderRepository.findWithItemsByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(inventoryService.restoreStock(99L, 1)).thenReturn(new StockLevel(99L, 5, 1L));
        when(inventoryService.restoreStock(PRODUCT_ID, 2)).thenReturn(new StockLevel(PRODUCT_ID, 12, 1L));
        when(orderRepository.saveAndFlush(order)).thenAnswer(invocation -> invocation.getArgument(0));

        adminOrderService.updateStatus(ORDER_ID, new UpdateOrderStatusCommand(OrderStatus.CANCELLED));

        var orderVerifier = inOrder(inventoryService);
        orderVerifier.verify(inventoryService).restoreStock(PRODUCT_ID, 2);
        orderVerifier.verify(inventoryService).restoreStock(99L, 1);
    }

    @Test
    void cancellingAConfirmedOrderRestoresInventory() {
        Order order = pendingOrder(2);
        order.transitionTo(OrderStatus.CONFIRMED);
        when(orderRepository.findWithItemsByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(inventoryService.restoreStock(PRODUCT_ID, 2)).thenReturn(new StockLevel(PRODUCT_ID, 12, 1L));
        when(orderRepository.saveAndFlush(order)).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = adminOrderService.updateStatus(
                ORDER_ID, new UpdateOrderStatusCommand(OrderStatus.CANCELLED));

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryService).restoreStock(PRODUCT_ID, 2);
    }

    @Test
    void updateStatusRejectsInvalidLifecycleTransitions() {
        Order order = pendingOrder(1);
        order.transitionTo(OrderStatus.CONFIRMED);
        order.transitionTo(OrderStatus.PROCESSING);
        when(orderRepository.findWithItemsByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> adminOrderService.updateStatus(
                        ORDER_ID, new UpdateOrderStatusCommand(OrderStatus.CANCELLED)))
                .isInstanceOf(OrderStatusTransitionException.class)
                .satisfies(ex -> {
                    OrderStatusTransitionException transition = (OrderStatusTransitionException) ex;
                    assertThat(transition.getFrom()).isEqualTo(OrderStatus.PROCESSING);
                    assertThat(transition.getTo()).isEqualTo(OrderStatus.CANCELLED);
                });

        verify(inventoryService, never()).restoreStock(anyLong(), anyInt());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateStatusRejectsUnknownOrders() {
        when(orderRepository.findWithItemsByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminOrderService.updateStatus(
                        ORDER_ID, new UpdateOrderStatusCommand(OrderStatus.CONFIRMED)))
                .isInstanceOf(OrderNotFoundException.class);

        verify(inventoryService, never()).restoreStock(anyLong(), anyInt());
    }

    private static Order pendingOrder(int quantity) {
        User customer = User.registerCustomer(
                "customer@example.com", "test-only-password-hash", "Ada", "Lovelace");
        ReflectionTestUtils.setField(customer, "id", 11L);
        Product keyboard = Product.create(
                "KB-001", "Keyboard", "keyboard", null, new BigDecimal("49.50"), null, 10, CATEGORY);
        ReflectionTestUtils.setField(keyboard, "id", PRODUCT_ID);
        ReflectionTestUtils.setField(keyboard, "version", 0L);
        Order order = Order.place("ORD-2026-000700", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, quantity);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }

    private static Order twoLineOrder() {
        User customer = User.registerCustomer(
                "customer@example.com", "test-only-password-hash", "Ada", "Lovelace");
        ReflectionTestUtils.setField(customer, "id", 11L);
        Product keyboard = Product.create(
                "KB-001", "Keyboard", "keyboard", null, new BigDecimal("49.50"), null, 10, CATEGORY);
        ReflectionTestUtils.setField(keyboard, "id", PRODUCT_ID);
        Product mouse = Product.create(
                "MS-001", "Mouse", "mouse", null, new BigDecimal("10.00"), null, 5, CATEGORY);
        ReflectionTestUtils.setField(mouse, "id", 99L);
        Order order = Order.place("ORD-2026-000700", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(mouse, 1);
        order.addItem(keyboard, 2);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return order;
    }
}
