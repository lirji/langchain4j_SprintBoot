package com.lrj.langchain4j.nl2sql;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NL2SQL 数字 grounding —— 确定性、零 LLM 的事后核对，仿 RAG grounding 的 Layer 0 套路：
 * 答案里出现的「数据数字」应当来自查询结果（或用户问题），否则可能是模型编的。<strong>纯函数、无 IO</strong>，
 * 可被 JUnit 确定性覆盖。
 *
 * <p>判为「受支撑」的来源：① 查询结果各 cell 的数值 ② 行数 {@code rowCount}（答案常说"共 N 笔"）
 * ③ 用户问题里的数字（用户给的，不算幻觉）。
 *
 * <p>为压假阳性，以下答案数字<strong>不</strong>核对（不视为幻觉）：
 * <ul>
 *   <li>绝对值 ≤ 10 的整数 —— 多是序数 / 计数 / "前 3 名"</li>
 *   <li>[1900,2099] 的四位整数 —— 多是年份</li>
 * </ul>
 * 数字归一：去千分位逗号、去小数尾零（{@code 5,400.00 → 5400}，{@code 0.50 → 0.5}），让 SQL 返回的
 * {@code 5400.00} 与答案里的 {@code 5,400} 能对上。
 */
public final class NumberGrounding {

    /** 抓数字：可带千分位逗号与小数。前后用断言避免截断更长 token 的一部分。 */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d[\\d,]*(?:\\.\\d+)?(?![\\w])");

    private NumberGrounding() {}

    /**
     * @return 答案里出现、但既不在查询结果/问题/行数中、又不属于豁免类的数字（去重保序）；空 = 全部有据。
     */
    public static List<String> unsupportedNumbers(String answer, List<Map<String, Object>> rows,
                                                  String question, int rowCount) {
        if (answer == null || answer.isBlank()) return List.of();

        Set<String> supported = new LinkedHashSet<>();
        supported.add(normalize(String.valueOf(rowCount)));
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row == null) continue;
                for (Object v : row.values()) {
                    if (v != null) addNumbers(supported, v.toString());
                }
            }
        }
        if (question != null) addNumbers(supported, question);

        Set<String> bad = new LinkedHashSet<>();
        Matcher m = NUMBER.matcher(answer);
        while (m.find()) {
            String norm = normalize(m.group());
            if (norm.isEmpty() || supported.contains(norm) || isExempt(norm)) continue;
            bad.add(norm);
        }
        return List.copyOf(bad);
    }

    private static void addNumbers(Set<String> out, String text) {
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String norm = normalize(m.group());
            if (!norm.isEmpty()) out.add(norm);
        }
    }

    /** 去千分位逗号 + 去小数尾零（及尾随小数点）。 */
    static String normalize(String raw) {
        String s = raw.replace(",", "").strip();
        if (s.isEmpty()) return "";
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 序数/计数（|n|≤10 整数）或年份（[1900,2099] 四位整数）—— 不核对。 */
    private static boolean isExempt(String norm) {
        if (norm.contains(".")) return false;
        try {
            long n = Long.parseLong(norm);
            if (Math.abs(n) <= 10) return true;
            return norm.length() == 4 && n >= 1900 && n <= 2099;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
