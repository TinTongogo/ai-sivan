package com.icusu.sivan.infra.knowledge.entity;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * 自定义 Hibernate UserType，将 float[] 序列化为文本字符串写入 PostgreSQL vector 列。
 * <p>
 * hibernate-vector 的 VectorJdbcType 使用 SqlTypes.VECTOR(10000)，PG JDBC 驱动无法识别此类型码，
 * 回退为 bytea 导致 "column is of type vector but expression is of type bytea" 错误。
 * 本类型以 varchar 写入 + 配合 @ColumnTransformer(write = "?::vector") 显式 SQL 类型转换绕过此问题。
 */
public class FloatArrayVectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        return Types.VARCHAR;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) {
        return Arrays.equals(x, y);
    }

    @Override
    public int hashCode(float[] x) {
        return Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner) throws SQLException {
        String value = rs.getString(position);
        if (value == null) {
            return null;
        }
        return parseVector(value);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.VARCHAR);
        } else {
            st.setString(index, Arrays.toString(value));
        }
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) {
        return value;
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) {
        return (float[]) cached;
    }

    @Override
    public float[] replace(float[] detached, float[] managed, Object owner) {
        return detached;
    }

    /**
     * 解析 PostgreSQL vector 文本表示 [x,y,z] 或 {x,y,z} 为 float[]。
     */
    private static float[] parseVector(String text) {
        String stripped = text.strip();
        if (stripped.startsWith("[") && stripped.endsWith("]")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        } else if (stripped.startsWith("{") && stripped.endsWith("}")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        if (stripped.isBlank()) {
            return new float[0];
        }
        String[] parts = stripped.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].strip());
        }
        return result;
    }
}
