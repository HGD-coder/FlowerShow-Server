package com.github.hgdcoder.flowershow.persistence.mapper.recommendation;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(float[].class)
public final class FloatVectorTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int index,
            float[] parameter,
            JdbcType jdbcType
    ) throws SQLException {
        statement.setString(index, toLiteral(parameter));
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return parse(resultSet.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return parse(resultSet.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    public static String toLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("Embedding vector must not be empty.");
        }
        StringBuilder value = new StringBuilder(vector.length * 12).append('[');
        for (int index = 0; index < vector.length; index++) {
            float item = vector[index];
            if (!Float.isFinite(item)) {
                throw new IllegalArgumentException("Embedding vector must contain finite values.");
            }
            if (index > 0) {
                value.append(',');
            }
            value.append(Float.toString(item));
        }
        return value.append(']').toString();
    }

    private static float[] parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() < 2
                || normalized.charAt(0) != '['
                || normalized.charAt(normalized.length() - 1) != ']') {
            throw new IllegalArgumentException("Stored embedding vector has an invalid representation.");
        }
        String body = normalized.substring(1, normalized.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }
        String[] values = body.split(",");
        float[] vector = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            float item = Float.parseFloat(values[index].strip());
            if (!Float.isFinite(item)) {
                throw new IllegalArgumentException("Stored embedding vector contains a non-finite value.");
            }
            vector[index] = item;
        }
        return vector;
    }
}
