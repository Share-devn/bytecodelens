package dev.share.bytecodelens.pattern.ast;

import java.util.regex.Pattern;

public sealed interface MatchSpec {

    boolean matches(String input);

    record Literal(String value) implements MatchSpec {
        @Override
        public boolean matches(String input) {
            return value.equals(input);
        }
    }

    record Regex(Pattern pattern, String source) implements MatchSpec {
        @Override
        public boolean matches(String input) {
            return input != null && pattern.matcher(input).find();
        }
    }

    record Wildcard() implements MatchSpec {
        @Override
        public boolean matches(String input) {
            return true;
        }
    }
}
