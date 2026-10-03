package com.demo.contract.rule.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在归一化文本里定位证据片段。
 *
 * <p>存在的意义：规则命中时必须给出"依据在原文的哪里"，
 * 否则人工复核只能看到一句"金额不一致"却无从核对。
 *
 * <p>{@link #locate} 的两条纪律：
 * <ol>
 *   <li><b>多处命中时取第一次出现</b>并标记 {@code ambiguous=true}，
 *       由调用方决定是否降级。不擅自选一个"看起来最合适"的位置。</li>
 *   <li><b>找不到就返回空</b>，不返回"最接近"的位置。
 *       给一个人工核验时会指向错误位置的区间，比不给位置更糟。</li>
 * </ol>
 */
public final class TextSearch {

    private TextSearch() {
    }

    /**
     * 定位片段。
     *
     * @param text   归一化文本
     * @param needle 要找的片段
     */
    public static Optional<Location> locate(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return Optional.empty();
        }
        int first = text.indexOf(needle);
        if (first < 0) {
            return Optional.empty();
        }
        int second = text.indexOf(needle, first + 1);
        return Optional.of(new Location(first, first + needle.length(), needle, second >= 0));
    }

    /**
     * 按关键字之一查找，用于"必备条款是否存在"这类判断。
     *
     * <p>关键字按给定顺序尝试，<b>返回第一个命中的关键字的位置</b>——
     * 顺序由调用方定义，因此结果是确定的。
     *
     * @return 命中的关键字与位置；全部未命中返回空
     */
    public static Optional<KeywordHit> locateAny(String text, List<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return Optional.empty();
        }
        for (String kw : keywords) {
            Optional<Location> loc = locate(text, kw);
            if (loc.isPresent()) {
                return Optional.of(new KeywordHit(kw, loc.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * 判断某段文本附近是否出现给定关键字。
     *
     * <p>用途：区分"提到了争议解决"与"只是目录里出现了一次"。
     * 真实实现应当按条款切分后再判断，当前用"关键字附近 N 字符内是否出现另一关键字"
     * 作为简化——<b>这是启发式，已知不精确</b>，保守方向是宁可判存在。
     *
     * @param window 以命中位置为中心的窗口半径（字符）
     */
    public static boolean hasNearby(String text, String anchor, List<String> nearKeywords, int window) {
        Optional<Location> anchorLoc = locate(text, anchor);
        if (anchorLoc.isEmpty()) {
            return false;
        }
        int from = Math.max(0, anchorLoc.get().start() - window);
        int to = Math.min(text.length(), anchorLoc.get().end() + window);
        String slice = text.substring(from, to);
        for (String kw : nearKeywords) {
            if (slice.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 统计片段出现次数。 */
    public static int count(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = text.indexOf(needle);
        while (i >= 0) {
            n++;
            i = text.indexOf(needle, i + needle.length());
        }
        return n;
    }

    /**
     * 按正则找出所有匹配位置，用于"提取所有疑似金额写法"这类场景。
     *
     * @return 每个匹配的 {@code [start, end)} 与文本
     */
    public static List<Location> findAll(String text, Pattern pattern) {
        List<Location> out = new ArrayList<>();
        if (text == null || pattern == null) {
            return out;
        }
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            out.add(new Location(m.start(), m.end(), m.group(), false));
        }
        return out;
    }

    /**
     * 证据位置。
     *
     * @param ambiguous 该片段在文本中出现多次——调用方应据此决定是否降级为人工
     */
    public record Location(int start, int end, String matched, boolean ambiguous) {
        public int length() {
            return end - start;
        }
    }

    /** 命中的关键字及其位置。 */
    public record KeywordHit(String keyword, Location location) {
    }

    /** 便于日志与测试阅读：把命中的关键字与位置整理成有序 Map。 */
    public static Map<String, int[]> summarize(List<KeywordHit> hits) {
        Map<String, int[]> map = new LinkedHashMap<>();
        for (KeywordHit h : hits) {
            map.put(h.keyword(), new int[]{h.location().start(), h.location().end()});
        }
        return map;
    }
}
