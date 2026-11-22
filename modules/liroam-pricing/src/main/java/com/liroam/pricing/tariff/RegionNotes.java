package com.liroam.pricing.tariff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.liroam.pricing.region.RegionDictionary;

/**
 * 资费表「备注」列与「注意事项」脚注 → 每个可用区域的实名标记与限制说明，以及卡产品级限制。
 * <ul>
 * <li>脚注以行首 {@code (i)}、{@code (ii)}… 分段；提到本表某些资源码的只归这些资源，一个资源码都没提的归全部资源；</li>
 * <li>脚注落到区域：备注里引用了它（如「实名认证 (i)」）的行所在区域 ∪ 脚注正文点名的区域；</li>
 * <li>两者都没有的脚注是卡产品级限制（如禁用于物联网）；</li>
 * <li>备注或所落脚注标题含「实名」即需实名。</li>
 * </ul>
 */
final class RegionNotes {

    private static final Pattern FOOTNOTE = Pattern.compile("(?m)^\\s*\\(([ivxlc]+)\\)");
    private static final String KYC_WORD = "实名";

    record Footnote(String marker, String title, String text) {

        boolean kyc() {
            return title.contains(KYC_WORD);
        }
    }

    record RegionNote(boolean kycRequired, String note) {
    }

    record Result(Map<String, RegionNote> byRegion, List<String> productNotes) {
    }

    private RegionNotes() {
    }

    static List<Footnote> footnotes(String notesRaw) {
        List<Footnote> list = new ArrayList<>();
        if (notesRaw == null) {
            return list;
        }
        Matcher m = FOOTNOTE.matcher(notesRaw);
        List<int[]> spans = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            markers.add(m.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : notesRaw.length();
            String text = notesRaw.substring(spans.get(i)[0], end).strip();
            String title = text.lines().findFirst().orElse(text);
            list.add(new Footnote(markers.get(i), title, text));
        }
        return list;
    }

    /**
     * @param remarksByRegion 本资源各区域（ISO 码）的行备注原文，无备注为空列表；键即本资源全部可用区域
     */
    static Result derive(String resource, Collection<String> sheetResources, List<Footnote> footnotes,
            Map<String, List<String>> remarksByRegion, RegionDictionary dictionary) {
        Map<String, Set<String>> notes = new LinkedHashMap<>();
        Map<String, Boolean> kyc = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : remarksByRegion.entrySet()) {
            Set<String> texts = new LinkedHashSet<>(e.getValue());
            notes.put(e.getKey(), texts);
            kyc.put(e.getKey(), texts.stream().anyMatch(t -> t.contains(KYC_WORD)));
        }
        List<String> productNotes = new ArrayList<>();
        for (Footnote f : footnotes) {
            if (!appliesTo(f, resource, sheetResources)) {
                continue;
            }
            String ref = "(" + f.marker() + ")";
            Set<String> targets = new LinkedHashSet<>();
            remarksByRegion.forEach((code, remarks) -> {
                if (remarks.stream().anyMatch(r -> r.contains(ref))) {
                    targets.add(code);
                }
            });
            dictionary.codesMentionedIn(f.text()).stream().filter(remarksByRegion::containsKey).forEach(targets::add);
            if (targets.isEmpty()) {
                productNotes.add(f.text());
                continue;
            }
            for (String code : targets) {
                notes.get(code).add(f.text());
                if (f.kyc()) {
                    kyc.put(code, true);
                }
            }
        }
        Map<String, RegionNote> byRegion = new LinkedHashMap<>();
        notes.forEach((code, texts) -> byRegion.put(code, new RegionNote(kyc.get(code), texts.isEmpty() ? null : String.join("\n", texts))));
        return new Result(byRegion, productNotes);
    }

    private static boolean appliesTo(Footnote f, String resource, Collection<String> sheetResources) {
        boolean mentionsAny = sheetResources.stream().anyMatch(f.text()::contains);
        return !mentionsAny || f.text().contains(resource);
    }
}
