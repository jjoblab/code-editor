package jo.codeeditor.find;

import java.util.*;
import java.util.regex.*;

/**
 * Find/replace operations supporting case-insensitive, whole-word, and regex modes.
 * Ported from CodeAssist FindReplace.kt.
 
 *
 * @since v1.0.0
*/
public final class FindReplace {

    private FindReplace() {}

    /**
     * Find all matches of query in text.
     */
    public static List<Match> findMatches(CharSequence text, String query, FindOptions opts) {
        if (query == null || query.isEmpty()) return Collections.emptyList();

        List<Match> matches = new ArrayList<>();

        if (opts.regex) {
            findRegex(text, query, opts, matches);
        } else if (opts.wholeWord) {
            findWholeWord(text, query, opts, matches);
        } else {
            findPlain(text, query, opts, matches);
        }

        return matches;
    }

    private static void findPlain(CharSequence text, String query, FindOptions opts, List<Match> out) {
        String haystack = text.toString();
        String needle = query;
        if (!opts.caseSensitive) {
            haystack = haystack.toLowerCase(java.util.Locale.ROOT);
            needle = needle.toLowerCase(java.util.Locale.ROOT);
        }
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) break;
            out.add(new Match(idx, idx + needle.length()));
            from = idx + 1;
        }
    }

    private static void findWholeWord(CharSequence text, String query, FindOptions opts, List<Match> out) {
        String haystack = text.toString();
        String needle = query;
        if (!opts.caseSensitive) {
            haystack = haystack.toLowerCase(java.util.Locale.ROOT);
            needle = needle.toLowerCase(java.util.Locale.ROOT);
        }
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) break;
            // Check word boundaries
            boolean leftBound = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            boolean rightBound = idx + needle.length() >= haystack.length()
                || !Character.isLetterOrDigit(haystack.charAt(idx + needle.length()));
            if (leftBound && rightBound) {
                out.add(new Match(idx, idx + needle.length()));
            }
            from = idx + 1;
        }
    }

    private static void findRegex(CharSequence text, String query, FindOptions opts, List<Match> out) {
        try {
            int flags = Pattern.MULTILINE;
            if (!opts.caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
            Pattern pattern = Pattern.compile(query, flags);
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                out.add(new Match(matcher.start(), matcher.end()));
            }
        } catch (PatternSyntaxException e) {
            // Invalid regex: return no matches
        }
    }

    /**
     * Find the index of the match at or after the given caret position.
     * Wraps around if no match is found after caret.
     *
     * @return match index, or -1 if no matches
     */
    public static int matchIndexFrom(List<Match> matches, int caret) {
        if (matches.isEmpty()) return -1;
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).start >= caret) return i;
        }
        return 0; // wrap around
    }
}
