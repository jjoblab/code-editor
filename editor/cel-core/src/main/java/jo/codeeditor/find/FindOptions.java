package jo.codeeditor.find;

/**
 * Options des opérations de recherche/remplacement.
 */
public final class FindOptions {
    public final boolean caseSensitive;
    public final boolean wholeWord;
    public final boolean regex;

    public FindOptions() {
        this(false, false, false);
    }

    public FindOptions(boolean caseSensitive, boolean wholeWord, boolean regex) {
        this.caseSensitive = caseSensitive;
        this.wholeWord = wholeWord;
        this.regex = regex;
    }

    public FindOptions withCaseSensitive(boolean cs) {
        return new FindOptions(cs, wholeWord, regex);
    }

    public FindOptions withWholeWord(boolean ww) {
        return new FindOptions(caseSensitive, ww, regex);
    }

    public FindOptions withRegex(boolean rx) {
        return new FindOptions(caseSensitive, wholeWord, rx);
    }

    @Override
    public String toString() {
        return "FindOptions(case=" + caseSensitive + ", whole=" + wholeWord + ", regex=" + regex + ")";
    }
}
