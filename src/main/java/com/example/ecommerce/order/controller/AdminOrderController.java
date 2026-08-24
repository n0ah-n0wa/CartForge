package com.example.ecommerce.order.controller;

import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.common.security.RequireAdmin;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.dto.UpdateOrderStatusCommand;
import com.example.ecommerce.order.service.AdminOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative order endpoints. Authorization is enforced both by the URL
 * matcher and by {@link RequireAdmin}.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@RequireAdmin
@Tag(name = "Admin Orders", description = "Administrator order listing and lifecycle")
@SecurityRequirement(name = "bearerAuth")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    @GetMapping
    @Operation(
            summary = "List all orders",
            description = "Returns a paginated list of every customer's orders. Optional "
                    + "status filter. Allowlisted sorting (createdAt, orderNumber, status, "
                    + "totalAmount); default is createdAt,desc. Page size is capped by "
                    + "app.pagination.max-page-size.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Paged order summaries")
    @ApiResponse(responseCode = "400", description = "Invalid page, size, sort, or status parameter")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    public PageResponse<OrderSummaryResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) OrderStatus status,
            HttpServletRequest request) {
        return adminOrderService.listOrders(page, size, sortParams(request), status);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Retrieve any order",
            description = "Returns a single order with snapshot line items. Not scoped by "
                    + "customer ownership.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Order with snapshot lines")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public OrderResponse get(@PathVariable Long id) {
        return adminOrderService.getOrder(id);
    }

    @PatchMapping("/{id}/status")
    @Operation(
            summary = "Update order status",
            description = "Applies one allowed lifecycle transition. Invalid transitions "
                    + "are rejected with 409. Cancelling a pending or confirmed order "
                    + "restores inventory in the same transaction. Concurrent updates "
                    + "are serialized by a row lock.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Order updated")
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Order not found")
    @ApiResponse(responseCode = "409", description = "Invalid status transition or inventory conflict")
    public OrderResponse updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusCommand command) {
        return adminOrderService.updateStatus(id, command);
    }

    private static List<String> sortParams(HttpServletRequest request) {
        String[] values = request.getParameterValues("sort");
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.asList(values);
    }
}
