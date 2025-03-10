/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.runtime;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.Locale;

/**
 * Utilities for converting SQL {@code LIKE} and {@code SIMILAR} operators
 * to regular expressions.
 */
public class Like {
  private static final String JAVA_REGEX_SPECIALS = "[]()|^-+*?{}$\\.";
  private static final String SQL_SIMILAR_SPECIALS = "[]()|^-+*_%?{}";
  private static final String [] REG_CHAR_CLASSES = {
      "[:ALPHA:]", "\\p{Alpha}",
      "[:alpha:]", "\\p{Alpha}",
      "[:UPPER:]", "\\p{Upper}",
      "[:upper:]", "\\p{Upper}",
      "[:LOWER:]", "\\p{Lower}",
      "[:lower:]", "\\p{Lower}",
      "[:DIGIT:]", "\\d",
      "[:digit:]", "\\d",
      "[:SPACE:]", " ",
      "[:space:]", " ",
      "[:WHITESPACE:]", "\\s",
      "[:whitespace:]", "\\s",
      "[:ALNUM:]", "\\p{Alnum}",
      "[:alnum:]", "\\p{Alnum}"
  };

  // It's important to have XDigit before Digit to match XDigit first
  // (i.e. see the posixRegexToPattern method)
  private static final String[] POSIX_CHARACTER_CLASSES = new String[] { "Lower", "Upper", "ASCII",
      "Alpha", "XDigit", "Digit", "Alnum", "Punct", "Graph", "Print", "Blank", "Cntrl", "Space" };

  private Like() {
  }

  /**
   * Translates a SQL LIKE pattern to Java regex pattern, with optional
   * escape string.
   */
  static String sqlToRegexLike(
      String sqlPattern,
      @Nullable CharSequence escapeStr) {
    final char escapeChar;
    if (escapeStr != null) {
      if (escapeStr.length() != 1) {
        throw invalidEscapeCharacter(escapeStr.toString());
      }
      escapeChar = escapeStr.charAt(0);
    } else {
      escapeChar = 0;
    }
    return sqlToRegexLike(sqlPattern, escapeChar);
  }

  /**
   * Translates a SQL LIKE pattern to Java regex pattern.
   */
  static String sqlToRegexLike(
      String sqlPattern,
      char escapeChar) {
    int i;
    final int len = sqlPattern.length();
    final StringBuilder javaPattern = new StringBuilder(len + len);
    for (i = 0; i < len; i++) {
      char c = sqlPattern.charAt(i);
      if (JAVA_REGEX_SPECIALS.indexOf(c) >= 0) {
        javaPattern.append('\\');
      }
      if (c == escapeChar) {
        if (i == (sqlPattern.length() - 1)) {
          throw invalidEscapeSequence(sqlPattern, i);
        }
        char nextChar = sqlPattern.charAt(i + 1);
        if ((nextChar == '_')
            || (nextChar == '%')
            || (nextChar == escapeChar)) {
          javaPattern.append(nextChar);
          i++;
        } else if (nextChar == '\\') {
          javaPattern.append("\\\\");
          i++;
        } else {
          throw invalidEscapeSequence(sqlPattern, i);
        }
      } else if (c == '_') {
        javaPattern.append('.');
      } else if (c == '%') {
        javaPattern.append("(?s:.*)");
      } else {
        javaPattern.append(c);
      }
    }
    return javaPattern.toString();
  }

  private static RuntimeException invalidEscapeCharacter(String s) {
    return new RuntimeException(
        "Invalid escape character '" + s + "'");
  }

  private static RuntimeException invalidEscapeSequence(String s, int i) {
    return new RuntimeException(
        "Invalid escape sequence '" + s + "', " + i);
  }

  private static void similarEscapeRuleChecking(
      String sqlPattern,
      char escapeChar) {
    if (escapeChar == 0) {
      return;
    }
    if (SQL_SIMILAR_SPECIALS.indexOf(escapeChar) >= 0) {
      // The escape character is a special character
      // SQL 2003 Part 2 Section 8.6 General Rule 3.b
      for (int i = 0; i < sqlPattern.length(); i++) {
        if (sqlPattern.charAt(i) == escapeChar) {
          if (i == (sqlPattern.length() - 1)) {
            throw invalidEscapeSequence(sqlPattern, i);
          }
          char c = sqlPattern.charAt(i + 1);
          if ((SQL_SIMILAR_SPECIALS.indexOf(c) < 0)
              && (c != escapeChar)) {
            throw invalidEscapeSequence(sqlPattern, i);
          }
        }
      }
    }

    // SQL 2003 Part 2 Section 8.6 General Rule 3.c
    if (escapeChar == ':') {
      int position;
      position = sqlPattern.indexOf("[:");
      if (position >= 0) {
        position = sqlPattern.indexOf(":]");
      }
      if (position < 0) {
        throw invalidEscapeSequence(sqlPattern, position);
      }
    }
  }

  private static RuntimeException invalidRegularExpression(
      String pattern, int i) {
    return new RuntimeException(
        "Invalid regular expression '" + pattern + "', index " + i);
  }

  private static int sqlSimilarRewriteCharEnumeration(
      String sqlPattern,
      StringBuilder javaPattern,
      int pos,
      char escapeChar) {
    int i;
    for (i = pos + 1; i < sqlPattern.length(); i++) {
      char c = sqlPattern.charAt(i);
      if (c == ']') {
        return i - 1;
      } else if (c == escapeChar) {
        i++;
        char nextChar = sqlPattern.charAt(i);
        if (SQL_SIMILAR_SPECIALS.indexOf(nextChar) >= 0) {
          if (JAVA_REGEX_SPECIALS.indexOf(nextChar) >= 0) {
            javaPattern.append('\\');
          }
          javaPattern.append(nextChar);
        } else if (escapeChar == nextChar) {
          javaPattern.append(nextChar);
        } else {
          throw invalidRegularExpression(sqlPattern, i);
        }
      } else if (c == '-') {
        javaPattern.append('-');
      } else if (c == '^') {
        javaPattern.append('^');
      } else if (sqlPattern.startsWith("[:", i)) {
        int numOfRegCharSets = REG_CHAR_CLASSES.length / 2;
        boolean found = false;
        for (int j = 0; j < numOfRegCharSets; j++) {
          if (sqlPattern.startsWith(REG_CHAR_CLASSES[j + j], i)) {
            javaPattern.append(REG_CHAR_CLASSES[j + j + 1]);

            i += REG_CHAR_CLASSES[j + j].length() - 1;
            found = true;
            break;
          }
        }
        if (!found) {
          throw invalidRegularExpression(sqlPattern, i);
        }
      } else if (SQL_SIMILAR_SPECIALS.indexOf(c) >= 0) {
        throw invalidRegularExpression(sqlPattern, i);
      } else {
        javaPattern.append(c);
      }
    }
    return i - 1;
  }

  /**
   * Translates a SQL SIMILAR pattern to Java regex pattern, with optional
   * escape string.
   */
  static String sqlToRegexSimilar(
      String sqlPattern,
      @Nullable CharSequence escapeStr) {
    final char escapeChar;
    if (escapeStr != null) {
      if (escapeStr.length() != 1) {
        throw invalidEscapeCharacter(escapeStr.toString());
      }
      escapeChar = escapeStr.charAt(0);
    } else {
      escapeChar = 0;
    }
    return sqlToRegexSimilar(sqlPattern, escapeChar);
  }

  /**
   * Translates SQL SIMILAR pattern to Java regex pattern.
   */
  static String sqlToRegexSimilar(
      String sqlPattern,
      char escapeChar) {
    similarEscapeRuleChecking(sqlPattern, escapeChar);

    boolean insideCharacterEnumeration = false;
    final StringBuilder javaPattern =
        new StringBuilder(sqlPattern.length() * 2);
    final int len = sqlPattern.length();
    for (int i = 0; i < len; i++) {
      char c = sqlPattern.charAt(i);
      if (c == escapeChar) {
        if (i == (len - 1)) {
          // It should never reach here after the escape rule
          // checking.
          throw invalidEscapeSequence(sqlPattern, i);
        }
        char nextChar = sqlPattern.charAt(i + 1);
        if (SQL_SIMILAR_SPECIALS.indexOf(nextChar) >= 0) {
          // special character, use \ to replace the escape char.
          if (JAVA_REGEX_SPECIALS.indexOf(nextChar) >= 0) {
            javaPattern.append('\\');
          }
          javaPattern.append(nextChar);
        } else if (nextChar == escapeChar) {
          javaPattern.append(nextChar);
        } else {
          // It should never reach here after the escape rule
          // checking.
          throw invalidEscapeSequence(sqlPattern, i);
        }
        i++; // we already process the next char.
      } else {
        switch (c) {
        case '_':
          javaPattern.append('.');
          break;
        case '%':
          javaPattern.append("(?s:.*)");
          break;
        case '[':
          javaPattern.append('[');
          insideCharacterEnumeration = true;
          i =
              sqlSimilarRewriteCharEnumeration(sqlPattern, javaPattern,
                  i, escapeChar);
          break;
        case ']':
          if (!insideCharacterEnumeration) {
            throw invalidRegularExpression(sqlPattern, i);
          }
          insideCharacterEnumeration = false;
          javaPattern.append(']');
          break;
        case '\\':
          javaPattern.append("\\\\");
          break;
        case '$':

          // $ is special character in java regex, but regular in
          // SQL regex.
          javaPattern.append("\\$");
          break;
        default:
          javaPattern.append(c);
        }
      }
    }
    if (insideCharacterEnumeration) {
      throw invalidRegularExpression(sqlPattern, len);
    }

    return javaPattern.toString();
  }

  static java.util.regex.Pattern posixRegexToPattern(String regex,
      boolean caseSensitive) {
    int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
    return posixRegexToPattern(regex, flags);
  }

  static java.util.regex.Pattern posixRegexToPattern(String regex, int flags) {
    // Replace existing character classes with java equivalent ones
    String originalRegex = regex;
    String[] existingExpressions = Arrays.stream(POSIX_CHARACTER_CLASSES)
        .filter(v -> originalRegex.contains(v.toLowerCase(Locale.ROOT)))
        .toArray(String[]::new);
    for (String v : existingExpressions) {
      regex = regex.replace(v.toLowerCase(Locale.ROOT), "\\p{" + v + "}");
    }

    return java.util.regex.Pattern.compile(regex, flags);
  }
}
