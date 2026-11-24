package it.comune.trieste.ouf.geopackage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** A binary64 ordinate with the ECMAScript JSON number spelling required by D-084. */
@JsonSerialize(using=GeometryCoordinate.Serializer.class)
public record GeometryCoordinate(double value) {
  public GeometryCoordinate {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("ONB_GEOMETRY_COORDINATE_INVALID");
  }

  @Override public String toString() {
    if (value == 0) return "0";
    // Java 21 supplies the shortest round-trip decimal. Its one-digit exception
    // can choose a closer two-digit decimal. ECMAScript prefers the one-digit form.
    BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
    if (decimal.precision() == 2) {
      var oneDigit = new BigDecimal(value).round(new MathContext(1, RoundingMode.HALF_EVEN));
      if (oneDigit.doubleValue() == value) decimal = oneDigit.stripTrailingZeros();
    }
    int exponent = decimal.precision() - decimal.scale() - 1;
    if (exponent >= -6 && exponent < 21) return decimal.toPlainString();
    return decimal.scaleByPowerOfTen(-exponent).toPlainString() + "e" + (exponent >= 0 ? "+" : "") + exponent;
  }

  public static final class Serializer extends JsonSerializer<GeometryCoordinate> {
    @Override public void serialize(GeometryCoordinate coordinate, JsonGenerator json, SerializerProvider provider) throws IOException {
      json.writeNumber(coordinate.toString());
    }
  }
}
