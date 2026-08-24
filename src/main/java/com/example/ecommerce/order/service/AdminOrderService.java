package com.example.ecommerce.order.service;

import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.PageRequests;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.dto.UpdateOrderStatusCommand;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.mapper.OrderMapper;
import com.example.ecommerce.order.repository.OrderRepository;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator order operations. Listing is not scoped by owner; status
 * changes go through the order lifecycle and restore inventory when an order
 * is cancelled.
 */
@Service
@Transactional
public class AdminOrderService {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderService.class);

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final OrderMapper orderMapper;
    private final ApplicationProperties properties;

    public AdminOrderService(
            OrderRepository orderRepository,
            InventoryService inventoryService,
            OrderMapper orderMapper,
            ApplicationProperties properties) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.orderMapper = orderMapper;
        this.properties = properties;
    }

    /**
     * Returns a paginated view of every order, optionally filtered by status.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listOrders(
            Integer page,
            Integer size,
            List<String> sortParams,
            OrderStatus status) {
        Sort resolvedSort = OrderSortSupport.ALLOWED.resolve(sortParams);
        Pageable pageable = PageRequests.of(
                page,
                size,
                properties.pagination().defaultPageSize(),
                properties.pagination().maxPageSize(),
                resolvedSort);
        Page<Order> result = status == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        return new PageResponse<>(
                result.getContent().stream().map(orderMapper::toSummaryResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Returns one order with snapshot lines, regardless of owner.
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return orderMapper.toResponse(order);
    }

    /**
     * Applies a lifecycle transition. The order row is locked so concurrent
     * customer cancellation and administrative updates cannot race. Cancelling
     * restores inventory for every line in the same transaction.
     */
    public OrderResponse updateStatus(Long orderId, UpdateOrderStatusCommand command) {
        Order order = orderRepository.findWithItemsByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        OrderStatus previous = order.getStatus();
        order.transitionTo(command.status());

        if (order.getStatus() == OrderStatus.CANCELLED) {
            order.getItems().stream()
                    .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                    .forEach(line -> inventoryService.restoreStock(line.getProduct().getId(), line.getQuantity()));
        }

        Order saved = orderRepository.saveAndFlush(order);
        log.info(
                "event=admin_order_status_changed orderId={} from={} to={}",
                saved.getId(),
                previous,
                saved.getStatus());
        return orderMapper.toResponse(saved);
    }
}
