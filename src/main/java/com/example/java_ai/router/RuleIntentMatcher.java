package com.example.java_ai.router;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 规则快筛：只放"百分之百确定"的短输入，宁可漏放不可错放，
 * 错放的输入绕过了 LLM 校验，是路由质量的最大杀手
 */
@Component
public class RuleIntentMatcher {

    private record Rule(Pattern pattern, RouteIntent intent) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(创建|提交|开一个|建一个).{0,6}(工单|ticket)", Pattern.CASE_INSENSITIVE), RouteIntent.ORDER),
            new Rule(Pattern.compile("工单[:：]?\\s*[A-Za-z0-9-]{4,}"), RouteIntent.ORDER),
            new Rule(Pattern.compile("(设备|网关|传感器)\\s*[：:]?\\s*[A-Za-z]{1,4}-\\d{3,}"), RouteIntent.DEVICE),
            new Rule(Pattern.compile("(查|看一下|查询).{0,8}(设备|告警)(状态|列表|详情)"), RouteIntent.DEVICE),
            new Rule(Pattern.compile("^(你好|您好|hi|hello|在吗|谢谢|再见)[!！。~～\\s]*$", Pattern.CASE_INSENSITIVE), RouteIntent.CHITCHAT)
    );

    public Optional<RouteIntent> match(String userMessage) {
        String msg = userMessage.trim();
        if (msg.length() > 30) {
            return Optional.empty();   // 长文本交给 LLM，规则只处理短指令
        }
        return RULES.stream()
                .filter(r -> r.pattern().matcher(msg).find())
                .map(Rule::intent)
                .findFirst();
    }
}
