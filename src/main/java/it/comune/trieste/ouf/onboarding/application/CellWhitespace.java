package it.comune.trieste.ouf.onboarding.application;

/** Unicode White_Space as fixed by ouf.value-types.v1 revision 2. */
final class CellWhitespace {
  private CellWhitespace() {}

  static String trim(String value) {
    int start = 0;
    int end = value.length();
    // All code points in this property are in the BMP.
    while (start < end && contains(value.charAt(start))) start++;
    while (end > start && contains(value.charAt(end - 1))) end--;
    return value.substring(start, end);
  }

  private static boolean contains(char c) {
    return (c >= 0x0009 && c <= 0x000d) || c == 0x0020 || c == 0x0085 || c == 0x00a0
        || c == 0x1680 || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029
        || c == 0x202f || c == 0x205f || c == 0x3000;
  }
}
