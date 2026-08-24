package com.example.ecommerce.order.controller;

import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.order.dto.CheckoutCommand;
import com.example.ecommerce.order.dto.CheckoutResult;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer order endpoints. Ownership always comes from the security context.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Authenticated customer orders and checkout")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    @Operation(
            summary = "List the authenticated customer's orders",
            description = "Returns a paginated order history for the authenticated customer. "
                    + "Each entry is a summary without line items; use GET /api/v1/orders/{id} "
                    + "for full snapshot lines. Supports bounded pagination and allowlisted sorting "
                    + "(createdAt, orderNumber, status, totalAmount). Default sort is createdAt,desc. "
                    + "Page size is capped by app.pagination.max-page-size.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Paged order summaries")
    @ApiResponse(responseCode = "400", description = "Invalid page, size, or sort parameter")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return orderService.listOrders(page, size, sortParams(request));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Retrieve one order",
            description = "Returns a single order with snapshot line items for the authenticated "
                    + "customer. Historical product name, SKU, and unit price are preserved even "
                    + "if the catalog has since changed.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Order with snapshot lines")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.getOrder(id);
    }

    @PostMapping
    @Operation(
            summary = "Checkout the current cart",
            description = "Creates an order from the authenticated customer's cart in a single "
                    + "transaction: validates active products and stock, snapshots commercial "
                    + "fields onto order lines, decrements inventory, clears the cart, and "
                    + "commits. Failures roll the entire unit of work back. Clients may send "
                    + "Idempotency-Key; the same user and key replay the original order when "
                    + "the body is equivalent and never create a second order.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Existing order returned for a matching Idempotency-Key")
    @ApiResponse(responseCode = "201", description = "Order created")
    @ApiResponse(responseCode = "400", description = "Validation failure, inactive product, or invalid Idempotency-Key")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "409", description = "Empty cart, insufficient stock, inventory conflict, or Idempotency-Key reused with a different body")
    public ResponseEntity<OrderResponse> checkout(
            @Valid @RequestBody CheckoutCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        CheckoutResult result = orderService.checkout(command, idempotencyKey);
        OrderResponse body = result.order();
        URI location = URI.create("/api/v1/orders/" + body.id());
        if (result.replayed()) {
            return ResponseEntity.ok().location(location).body(body);
        }
        return ResponseEntity.created(location).body(body);
    }

    @PostMapping("/{id}/cancel")
    @Operation(
            summary = "Cancel an order",
            description = "Cancels a pending or confirmed order for the authenticated customer. "
                    + "Snapshot line items and totals are preserved; inventory is restored "
                    + "transactionally. Orders that have shipped or been delivered cannot be "
                    + "cancelled. Concurrent cancellation attempts are serialized by a row lock.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Order cancelled")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @ApiResponse(responseCode = "409", description = "Order cannot be cancelled in its current status or inventory conflict")
    public OrderResponse cancel(@PathVariable Long id) {
        return orderService.cancelOrder(id);
    }

    private static List<String> sortParams(HttpServletRequest request) {
        String[] values = request.getParameterValues("sort");
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.asList(values);
    }
}
