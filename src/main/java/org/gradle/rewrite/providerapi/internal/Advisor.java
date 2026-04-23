package org.gradle.rewrite.providerapi.internal;

import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for emitting multi-line block-comment TODO advisors as a prefix on an expression node,
 * instead of OpenRewrite's default {@code SearchResult} {@code /*~~(...)~~>*\/} inline wrapper.
 *
 * <p>Rationale: {@code SearchResult} collapses its message into a single-line block-comment
 * marker that sits immediately before the target node, producing one enormous line for long
 * guidance messages. A plain block comment prepended to the expression's prefix whitespace
 * renders as a normal multi-line Java/Kotlin TODO, which IDEs highlight and humans can skim.
 */
public final class Advisor {

    private Advisor() {}

    /**
     * Prepend a {@code TODO:} block comment to the expression's prefix whitespace. The message
     * may contain newlines — each line gets the standard {@code " * "} Javadoc-style continuation.
     * Indentation is inferred from the whitespace already on the node's prefix so the rendered
     * comment lines up with the following code.
     */
    public static <J2 extends J> J2 addTodo(J2 expr, String message) {
        Space prefix = expr.getPrefix();
        String indent = indentFrom(prefix.getWhitespace());
        TextComment comment = buildTodo(message, indent);
        // Idempotency guard: if an identical TextComment is already in the prefix (from a previous
        // recipe cycle that OpenRewrite runs to verify convergence), skip. Without this the test
        // harness sees "took more than one cycle" and fails, and real runs stack duplicate TODOs.
        for (Comment existing : prefix.getComments()) {
            if (existing instanceof TextComment
                    && ((TextComment) existing).getText().equals(comment.getText())) {
                return expr;
            }
        }
        List<Comment> newComments = new ArrayList<>(prefix.getComments().size() + 1);
        newComments.add(comment);
        newComments.addAll(prefix.getComments());
        return (J2) expr.withPrefix(prefix.withComments(newComments));
    }

    private static TextComment buildTodo(String message, String indent) {
        StringBuilder sb = new StringBuilder("\n");
        String[] lines = message.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sb.append(indent).append(" * ");
            if (i == 0) sb.append("TODO: ");
            sb.append(lines[i]).append("\n");
        }
        sb.append(indent).append(" ");
        // suffix places cursor back at the expression's indent on a new line
        return new TextComment(true, sb.toString(), "\n" + indent, Markers.EMPTY);
    }

    /**
     * Return the leading whitespace (tabs / spaces) following the LAST newline in {@code ws}.
     * If {@code ws} has no newline, return empty string — the expression is inline and we'll emit
     * the comment without indentation.
     */
    static String indentFrom(String ws) {
        int lastNl = ws.lastIndexOf('\n');
        String tail = lastNl < 0 ? ws : ws.substring(lastNl + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == ' ' || c == '\t') sb.append(c); else break;
        }
        return sb.toString();
    }
}
