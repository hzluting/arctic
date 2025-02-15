package com.netease.arctic.utils;

import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

public class ArcticDataFiles {
  public static final OffsetDateTime EPOCH = Instant.ofEpochSecond(0).atOffset(ZoneOffset.UTC);
  public static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd-hh");
  private static final int EPOCH_YEAR = EPOCH.getYear();
  private static final String HIVE_NULL = "__HIVE_DEFAULT_PARTITION__";
  private static final String MONTH_TYPE = "month";
  private static final String HOUR_TYPE = "hour";

  /**
   * return the number of months away from the epoch, reverse {@link TransformUtil#humanMonth}
   */
  public static Integer readMonthData(String dateStr) {
    String[] dateParts = dateStr.split("-", -1);
    int year = Integer.parseInt(dateParts[0]);
    int month = Integer.parseInt(dateParts[1]);
    return Math.multiplyExact((year - EPOCH_YEAR), 12) + month - 1;
  }

  /**
   * return the number of hours away from the epoch, reverse {@link TransformUtil#humanHour}
   */
  private static Integer readHoursData(String asString) {
    try {
      SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
      Date date = SDF.parse(asString);
      OffsetDateTime parse = OffsetDateTime.parse(date.toInstant().toString());
      return Math.toIntExact(ChronoUnit.HOURS.between(EPOCH, parse));
    } catch (ParseException e) {
      throw new UnsupportedOperationException("Failed to parse date string:" + asString);
    }
  }

  public static Object fromPartitionString(PartitionField field, Type type, String asString) {
    if (asString == null || HIVE_NULL.equals(asString)) {
      return null;
    }

    switch (type.typeId()) {
      case BOOLEAN:
        return Boolean.valueOf(asString);
      case INTEGER:
        if (MONTH_TYPE.equals(field.transform().toString())) {
          return readMonthData(asString);
        } else if (HOUR_TYPE.equals(field.transform().toString())) {
          return readHoursData(asString);
        }
        return Integer.valueOf(asString);
      case STRING:
        return asString;
      case LONG:
        return Long.valueOf(asString);
      case FLOAT:
        return Float.valueOf(asString);
      case DOUBLE:
        return Double.valueOf(asString);
      case UUID:
        return UUID.fromString(asString);
      case FIXED:
        Types.FixedType fixed = (Types.FixedType) type;
        return Arrays.copyOf(
            asString.getBytes(StandardCharsets.UTF_8), fixed.length());
      case BINARY:
        return asString.getBytes(StandardCharsets.UTF_8);
      case DECIMAL:
        return new BigDecimal(asString);
      case DATE:
        return Literal.of(asString).to(Types.DateType.get()).value();
      default:
        throw new UnsupportedOperationException(
            "Unsupported type for fromPartitionString: " + type);
    }
  }

  public static GenericRecord data(PartitionSpec spec, String partitionPath) {
    GenericRecord data = GenericRecord.create(spec.schema());
    String[] partitions = partitionPath.split("/", -1);
    Preconditions.checkArgument(partitions.length <= spec.fields().size(),
        "Invalid partition data, too many fields (expecting %s): %s",
        spec.fields().size(), partitionPath);
    Preconditions.checkArgument(partitions.length >= spec.fields().size(),
        "Invalid partition data, not enough fields (expecting %s): %s",
        spec.fields().size(), partitionPath);

    for (int i = 0; i < partitions.length; i += 1) {
      PartitionField field = spec.fields().get(i);
      String[] parts = partitions[i].split("=", 2);
      Preconditions.checkArgument(parts.length == 2 &&
              parts[0] != null &&
              field.name().equals(parts[0]),
          "Invalid partition: %s", partitions[i]);

      data.set(i, ArcticDataFiles.fromPartitionString(field, spec.partitionType().fieldType(parts[0]), parts[1]));
    }

    return data;
  }
}
