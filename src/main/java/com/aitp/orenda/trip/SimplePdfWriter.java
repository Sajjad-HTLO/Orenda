package com.aitp.orenda.trip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal dependency-free PDF writer producing valid single- or multi-page A4
 * documents using the standard Helvetica font. Non-WinAnsi (Turkish etc.)
 * characters are ASCII-transliterated because no font is embedded; the ICS
 * export keeps full Unicode.
 */
final class SimplePdfWriter {

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN_LEFT = 50;
    private static final int MARGIN_TOP = 800;
    private static final int MARGIN_BOTTOM = 50;
    private static final int LINE_HEIGHT = 15;
    private static final int MAX_CHARS_PER_LINE = 90;

    private SimplePdfWriter() {
    }

    static byte[] write(String title, List<String> lines) {
        List<List<String>> pages = paginate(title, lines);
        List<String> objects = new ArrayList<>();
        int fontObj = 3 + pages.size() * 2;

        objects.add("<< /Type /Catalog /Pages 2 0 R >>");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) kids.append(" ");
            kids.append(3 + i * 2).append(" 0 R");
        }
        objects.add("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>");

        for (int i = 0; i < pages.size(); i++) {
            int pageObj = 3 + i * 2;
            int contentObj = 4 + i * 2;
            String content = renderPage(pages.get(i), i == 0 ? title : null);
            objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT
                    + "] /Contents " + contentObj + " 0 R /Resources << /Font << /F1 " + fontObj
                    + " 0 R >> >> >>");
            objects.add("<< /Length " + content.getBytes(StandardCharsets.ISO_8859_1).length
                    + " >>\nstream\n" + content + "\nendstream");
        }
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");

        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(sb.length());
            sb.append(i + 1).append(" 0 obj\n");
            sb.append(objects.get(i));
            sb.append("\nendobj\n");
        }
        int xrefPos = sb.length();
        sb.append("xref\n0 ").append(objects.size() + 1).append("\n");
        sb.append("0000000000 65535 f \n");
        for (int offset : offsets) {
            sb.append(String.format("%010d 00000 n \n", offset));
        }
        sb.append("trailer\n<< /Size ").append(objects.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n").append(xrefPos).append("\n%%EOF");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static List<List<String>> paginate(String title, List<String> lines) {
        int maxLinesPerPage = (MARGIN_TOP - MARGIN_BOTTOM) / LINE_HEIGHT;
        List<List<String>> pages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int used = 0;
        if (title != null) {
            used = 2; // title + blank gap
        }
        for (String line : lines) {
            for (String wrapped : wrap(line)) {
                if (used + 1 > maxLinesPerPage) {
                    pages.add(current);
                    current = new ArrayList<>();
                    used = 0;
                }
                current.add(wrapped);
                used++;
            }
        }
        if (!current.isEmpty() || pages.isEmpty()) {
            pages.add(current);
        }
        return pages;
    }

    private static String renderPage(List<String> lines, String title) {
        StringBuilder sb = new StringBuilder();
        int y = MARGIN_TOP;
        if (title != null) {
            sb.append("BT /F1 14 Tf ").append(MARGIN_LEFT).append(" ").append(y)
                    .append(" Td (").append(escape(title)).append(") Tj ET\n");
            y -= 24;
        }
        for (String line : lines) {
            sb.append("BT /F1 11 Tf ").append(MARGIN_LEFT).append(" ").append(y)
                    .append(" Td (").append(escape(line)).append(") Tj ET\n");
            y -= LINE_HEIGHT;
        }
        return sb.toString();
    }

    private static List<String> wrap(String line) {
        List<String> wrapped = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            wrapped.add("");
            return wrapped;
        }
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(start + MAX_CHARS_PER_LINE, line.length());
            wrapped.add(line.substring(start, end));
            start = end;
        }
        return wrapped;
    }

    private static String escape(String text) {
        String safe = transliterate(text);
        return safe.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    private static String transliterate(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            char mapped = switch (c) {
                case 'ğ', 'Ğ' -> 'g';
                case 'ı' -> 'i';
                case 'İ' -> 'I';
                case 'ş', 'Ş' -> 's';
                case 'ç', 'Ç' -> 'c';
                case 'ö', 'Ö' -> 'o';
                case 'ü', 'Ü' -> 'u';
                case 'â', 'Â' -> 'a';
                case 'î', 'Î' -> 'i';
                case 'û', 'Û' -> 'u';
                default -> c;
            };
            if (mapped < 32 || mapped > 126) {
                mapped = '?';
            }
            sb.append(mapped);
        }
        return sb.toString();
    }
}