package com.lrj.langchain4j.controller;

import com.lrj.langchain4j.workflow.WorkflowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 退款审批工作流端点。仅在 {@code app.workflow.enabled=true} 时注册（{@link WorkflowService} 才存在）。
 * 不依赖任何外部渠道即可端到端跑通；接飞书渠道后改为 UserTask 推交互卡片 + 终态主动回推。
 *
 * <p>RBAC：发起（{@code /refund/start}）普通用户即可（任意已认证 key，跟 {@code /chat} 同级）；
 * 审批相关（{@code /tasks*}）必须带 {@code SCOPE_approve}（审批人专用 key）。租户隔离在
 * {@link WorkflowService} 里按流程变量 {@code tenantId} 兜底，controller 不感知。
 */
@RestController
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * 发起退款流程。body {@code {"message":"...","chatId":"u1","dedupeId":"...","webhookUrl":"..."}}
     * （chatId / dedupeId / webhookUrl 均可选）。
     * 传 {@code dedupeId}（渠道消息 id）则按 {@code tenant:chatId:dedupeId} 幂等去重，重推同一诉求只起一个流程；
     * 传 {@code webhookUrl} 则流程终态时把答复经 outbox 可靠回推（#8），否则客户端轮询 status 端点。
     */
    @PostMapping("/workflow/refund/start")
    public WorkflowService.StartResult start(@RequestBody Map<String, String> body) {
        return workflowService.start(
                body.getOrDefault("chatId", "default"),
                body.getOrDefault("message", ""),
                body.get("dedupeId"),
                body.get("webhookUrl"));
    }

    /** 本租户待审任务列表。 */
    @GetMapping("/workflow/tasks")
    @PreAuthorize("hasAuthority('SCOPE_approve')")
    public List<WorkflowService.TaskView> tasks() {
        return workflowService.listTasks();
    }

    /** 认领任务（#7）：设 assignee=当前用户，避免多人审同一单；已被他人领 → 409。 */
    @PostMapping("/workflow/tasks/{taskId}/claim")
    @PreAuthorize("hasAuthority('SCOPE_approve')")
    public WorkflowService.TaskView claim(@PathVariable String taskId) {
        return workflowService.claim(taskId);
    }

    /** 取消认领（#7）：放回待领池。 */
    @PostMapping("/workflow/tasks/{taskId}/unclaim")
    @PreAuthorize("hasAuthority('SCOPE_approve')")
    public void unclaim(@PathVariable String taskId) {
        workflowService.unclaim(taskId);
    }

    /** 完成审批。body {@code {"approved":true,"comment":"..."}}。并发双重审批 → 409（#7）。 */
    @PostMapping("/workflow/tasks/{taskId}/complete")
    @PreAuthorize("hasAuthority('SCOPE_approve')")
    public WorkflowService.CompleteResult complete(@PathVariable String taskId,
                                                   @RequestBody Map<String, Object> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        Object comment = body.get("comment");
        return workflowService.complete(taskId, approved, comment == null ? null : comment.toString());
    }

    /** 查实例状态 + reply。 */
    @GetMapping("/workflow/instances/{instanceId}")
    public WorkflowService.InstanceView instance(@PathVariable String instanceId) {
        return workflowService.getInstance(instanceId);
    }

    /**
     * PII 合规删除（#10）：清除本租户某 {@code chatId} 下所有工作流持久化数据（运行/历史实例 + reply + outbox）。
     * 需 {@code SCOPE_approve}（破坏性操作）。返回 {@code {chatId, purgedInstances}}。
     */
    @DeleteMapping("/workflow/data")
    @PreAuthorize("hasAuthority('SCOPE_approve')")
    public Map<String, Object> purge(@RequestParam String chatId) {
        int n = workflowService.purge(chatId);
        return Map.of("chatId", chatId, "purgedInstances", n);
    }
}
