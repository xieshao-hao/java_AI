package com.example.java_ai;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 电商业务工具集：订单查询、物流查询、退款申请。
 * 工具的 description 即路由规则——写清"何时调用"直接决定模型选择工具的准确率。
 */
@Component
public class OrderTools {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MockOrderRepository orderRepository;
    private final ToolCallTracker toolCallTracker;

    public OrderTools(MockOrderRepository orderRepository, ToolCallTracker toolCallTracker) {
        this.orderRepository = orderRepository;
        this.toolCallTracker = toolCallTracker;
    }

    @Tool(name = "getOrderInfo", description = "查询电商订单的详细信息（商品、金额、状态、下单时间），当用户提供订单号、或询问订单状态/订单详情时调用")
    public String getOrderInfo(@ToolParam(description = "订单号，例如 A10001") String orderNo,
                               ToolContext toolContext) {
        notifyToolCall(toolContext, "getOrderInfo", orderNo);
        MockOrderRepository.OrderInfo order = orderRepository.findByOrderNo(normalizeOrderNo(orderNo));
        if (order == null) {
            return "未找到订单【" + orderNo + "】。请告知用户核对订单号后重试，不要编造订单信息";
        }
        return "订单号：" + order.orderNo()
                + "\n商品：" + order.product()
                + "\n金额：￥" + order.amount()
                + "\n状态：" + order.status()
                + "\n下单时间：" + order.createdAt().format(FORMATTER)
                + (order.logisticsNo() == null ? "" : "\n物流单号：" + order.logisticsNo());
    }

    @Tool(name = "getOrderList", description = "查询某用户名下的全部订单列表，当用户询问\"我的订单\"、\"我买过什么\"但未提供具体订单号时调用")
    public String getOrderList(@ToolParam(description = "用户ID，例如 u001；当前演示账号固定为 u001") String userId,
                               ToolContext toolContext) {
        notifyToolCall(toolContext, "getOrderList", userId);
        List<MockOrderRepository.OrderInfo> orders = orderRepository.findByUserId(normalizeUserId(userId));
        if (orders.isEmpty()) {
            return "用户【" + userId + "】名下没有订单";
        }
        return orders.stream()
                .map(o -> o.orderNo() + " | " + o.product() + " | ￥" + o.amount()
                        + " | " + o.status() + " | 下单于 " + o.createdAt().format(FORMATTER))
                .collect(Collectors.joining("\n", "该用户共 " + orders.size() + " 笔订单：\n", ""));
    }

    @Tool(name = "getLogistics", description = "查询订单的物流配送轨迹，当用户询问快递到哪了、物流进度、什么时候送到时调用；若用户未提供订单号，先请用户提供订单号再调用")
    public String getLogistics(@ToolParam(description = "订单号，例如 A10001") String orderNo,
                               ToolContext toolContext) {
        notifyToolCall(toolContext, "getLogistics", orderNo);
        MockOrderRepository.OrderInfo order = orderRepository.findByOrderNo(normalizeOrderNo(orderNo));
        if (order == null) {
            return "未找到订单【" + orderNo + "】。请告知用户核对订单号后重试，不要编造物流信息";
        }
        if (order.logisticsNo() == null) {
            return "订单【" + orderNo + "】当前状态为【" + order.status() + "】，尚未发货，暂无物流信息";
        }
        List<MockOrderRepository.LogisticsNode> nodes = orderRepository.findLogistics(order.logisticsNo());
        if (nodes == null || nodes.isEmpty()) {
            return "订单【" + orderNo + "】的物流单号 " + order.logisticsNo() + " 暂无轨迹信息";
        }
        return nodes.stream()
                .map(n -> n.time() + " - " + n.description())
                .collect(Collectors.joining("\n",
                        "订单【" + orderNo + "】物流轨迹（单号 " + order.logisticsNo() + "）：\n", ""));
    }

    @Tool(name = "applyRefund", description = "为订单发起退款申请，当用户明确要求退款或退货时立即调用，不要向用户追问退款原因（用户未说明原因时直接调用即可）。规则：待发货和已发货订单可直接退款；退款中的订单勿重复提交；已完成订单需转人工处理。建议先调用 getOrderInfo 确认订单状态")
    public String applyRefund(@ToolParam(description = "订单号") String orderNo,
                              @ToolParam(description = "退款原因；用户未说明时可不传", required = false) String reason,
                              ToolContext toolContext) {
        String refundReason = (reason == null || reason.isBlank()) ? "用户主动申请退款" : reason.trim();
        notifyToolCall(toolContext, "applyRefund", orderNo + "，原因：" + refundReason);
        MockOrderRepository.OrderInfo order = orderRepository.findByOrderNo(normalizeOrderNo(orderNo));
        if (order == null) {
            return "未找到订单【" + orderNo + "】。请告知用户核对订单号后重试，不要编造退款结果";
        }
        return switch (order.status()) {
            case MockOrderRepository.STATUS_PENDING_SHIPMENT, MockOrderRepository.STATUS_SHIPPED -> {
                orderRepository.updateStatus(order.orderNo(), MockOrderRepository.STATUS_REFUNDING);
                yield "订单【" + order.orderNo() + "】（" + order.product() + "，￥" + order.amount()
                        + "）退款申请已提交，原因：" + refundReason + "。预计 1-3 个工作日原路退回。请告知用户退款进度可在订单详情中查看";
            }
            case MockOrderRepository.STATUS_REFUNDING ->
                    "订单【" + order.orderNo() + "】已在退款流程中，请告知用户无需重复提交，耐心等待处理结果";
            case MockOrderRepository.STATUS_COMPLETED ->
                    "订单【" + order.orderNo() + "】已完成，已超出自助退款期限。请告知用户已为其转接人工客服处理";
            default ->
                    "订单【" + order.orderNo() + "】当前状态为【" + order.status() + "】，不支持自助退款。请告知用户如有疑问可联系人工客服";
        };
    }

    /** 订单号规范化：去首尾空格并转大写，提升容错 */
    private String normalizeOrderNo(String input) {
        return input == null ? "" : input.trim().toUpperCase();
    }

    /** 用户ID规范化：仅去首尾空格。ID 区分大小写（u001 ≠ U001），不能转大写 */
    private String normalizeUserId(String input) {
        return input == null ? "" : input.trim();
    }

    private void notifyToolCall(ToolContext toolContext, String toolName, String args) {
        if (toolContext == null) {
            return;
        }
        Object conversationId = toolContext.getContext().get("conversationId");
        if (conversationId != null) {
            toolCallTracker.emit(conversationId.toString(), toolName, args);
        }
    }
}
