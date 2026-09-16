package jo.codeeditor.highlight;

/**
 * Optional TextMate-based tokenizer interface (v2.43).
 *
 * <p>v2.46 — Module isolation: the reference implementation has moved to
 * the {@code :tm4e} module ({@code jo.codeeditor.tm4e.TextMateTokenizerImpl}).
 * The {@code :tm4e} module is now an <em>optional</em> library module that
 * depends only on {@code :core}. Consumers of code-editor can choose:
 * <ul>
 *   <li><b>With TextMate</b> — depend on {@code :tm4e} (transitively pulls
 *       in the vendored tm4e source + grammar/theme assets). The 17 grammar
 *       files (Java, Kotlin, XML, HTML, CSS, JS, TS, Python, etc.) are
 *       loaded lazily from {@code assets/textmate/} on first
 *       {@link #isAvailable(String)} call.</li>
 *   <li><b>Without TextMate</b> — omit {@code :tm4e} entirely. The built-in
 *       tokenizer in {@link SyntaxHighlighter} ({@code :core}) handles all
 *       common languages (Java, Kotlin, XML, HTML, CSS, JS, TS, Python, Go,
 *       Rust, C, C++, Shell, YAML, etc.) with a per-language switch-case
 *       state machine. Quality is slightly lower than TextMate (no scope
 *       inheritance, no injections) but still useful for code editing.</li>
 * </ul>
 *
 * <p>If no implementation is registered (e.g., in pure-JVM tests, or before
 * app initialization), {@link SyntaxHighlighter#styleLine} falls back to
 * its built-in per-language switch-case tokenizer — the TextMate path is
 * strictly opt-in per language key.
 *
 * <h2>State model</h2>
 * <ul>
 *   <li>{@code entryState == 0} → start of file (fresh state, no previous line)</li>
 *   <li>{@code entryState > 0} → opaque index previously returned as
 *       {@code exitState} by the same tokenizer. The implementation maintains
 *       an internal {@code Map<Integer, IStateStack>} per language to look up
 *       the corresponding tm4e opaque state.</li>
 *   <li>The returned {@code exitState} must be <em>stable</em>: re-tokenizing
 *       the same line with the same {@code entryState} must yield the same
 *       {@code exitState}, so that the editor stops re-tokenizing downstream
 *       once it stabilizes.</li>
 * </ul>
 *
 * @since v2.43
 */
public interface TextMateTokenizer {

    /**
     * Returns {@code true} if a TextMate grammar is registered for the
     * given language key (e.g., {@code "java"}, {@code "kotlin"}).
     *
     * <p>If {@code true}, {@link SyntaxHighlighter#styleLine} delegates to
     * {@link #tokenize(String, int, String)} for this language. If
     * {@code false}, the built-in switch-case tokenizer is used instead.
     */
    boolean isAvailable(String language);

    /**
     * Tokenize a single line.
     *
     * @param line       the line text (without trailing newline)
     * @param entryState opaque lexer state entering this line (0 = fresh)
     * @param language   language key (e.g., {@code "java"}, {@code "kotlin"})
     * @return a {@link StyledLine} with spans and a stable exit state
     */
    StyledLine tokenize(String line, int entryState, String language);
}
