package com.kudosol.ai.inference.step;

import com.kudosol.ai.inference.exception.BadRequestException;

import java.util.List;

public final class ArrayUtils {

    private ArrayUtils() {
    }

    public static double[] toDoubleArray(Object value) {
        if (value instanceof double[] arr) return arr;
        if (value instanceof float[] arr) {
            double[] result = new double[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof int[] arr) {
            double[] result = new double[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof long[] arr) {
            double[] result = new double[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof List<?> list) {
            double[] result = new double[list.size()];
            for (int i = 0; i < list.size(); i++) result[i] = ((Number) list.get(i)).doubleValue();
            return result;
        }
        throw new BadRequestException("无法转换为 double[]: " + value.getClass().getName());
    }

    public static float[] toFloatArray(Object value) {
        if (value instanceof float[] arr) return arr;
        if (value instanceof double[] arr) {
            float[] result = new float[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = (float) arr[i];
            return result;
        }
        if (value instanceof int[] arr) {
            float[] result = new float[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof long[] arr) {
            float[] result = new float[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = (float) arr[i];
            return result;
        }
        if (value instanceof List<?> list) {
            float[] result = new float[list.size()];
            for (int i = 0; i < list.size(); i++) result[i] = ((Number) list.get(i)).floatValue();
            return result;
        }
        throw new BadRequestException("无法转换为 float[]: " + value.getClass().getName());
    }

    public static long[] toLongArray(Object value) {
        if (value instanceof long[] arr) return arr;
        if (value instanceof int[] arr) {
            long[] result = new long[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof double[] arr) {
            long[] result = new long[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = (long) arr[i];
            return result;
        }
        if (value instanceof float[] arr) {
            long[] result = new long[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = (long) arr[i];
            return result;
        }
        if (value instanceof List<?> list) {
            long[] result = new long[list.size()];
            for (int i = 0; i < list.size(); i++) result[i] = ((Number) list.get(i)).longValue();
            return result;
        }
        throw new BadRequestException("无法转换为 long[]: " + value.getClass().getName());
    }

    public static int[] toIntArray(Object value) {
        if (value instanceof int[] arr) return arr;
        if (value instanceof long[] arr) {
            int[] result = new int[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = (int) arr[i];
            return result;
        }
        if (value instanceof double[] arr) {
            int[] result = new int[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = (int) arr[i];
            return result;
        }
        if (value instanceof float[] arr) {
            int[] result = new int[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = (int) arr[i];
            return result;
        }
        if (value instanceof List<?> list) {
            int[] result = new int[list.size()];
            for (int i = 0; i < list.size(); i++) result[i] = ((Number) list.get(i)).intValue();
            return result;
        }
        throw new BadRequestException("无法转换为 int[]: " + value.getClass().getName());
    }

    public static double[] flattenToDouble(Object value) {
        if (value instanceof double[] arr) return arr;
        if (value instanceof double[][] arr) return flatten2DDouble(arr);
        if (value instanceof float[] arr) {
            double[] result = new double[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof float[][] arr) {
            int rows = arr.length, cols = arr[0].length;
            double[] result = new double[rows * cols];
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++)
                    result[i * cols + j] = arr[i][j];
            return result;
        }
        if (value instanceof int[] arr) {
            double[] result = new double[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof long[] arr) {
            double[] result = new double[arr.length];
            for (int i = 0; i < arr.length; i++) result[i] = arr[i];
            return result;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return new double[0];
            Object first = list.get(0);
            if (first instanceof List<?>) {
                int rows = list.size();
                int cols = ((List<?>) first).size();
                double[] result = new double[rows * cols];
                for (int i = 0; i < rows; i++) {
                    List<?> row = (List<?>) list.get(i);
                    for (int j = 0; j < cols; j++)
                        result[i * cols + j] = ((Number) row.get(j)).doubleValue();
                }
                return result;
            }
            double[] result = new double[list.size()];
            for (int i = 0; i < list.size(); i++)
                result[i] = ((Number) list.get(i)).doubleValue();
            return result;
        }
        throw new BadRequestException("无法展平为 double[]: " + value.getClass().getName());
    }

    public static float[] flattenToFloat(Object value) {
        if (value instanceof float[] arr) return arr;
        if (value instanceof float[][] arr) {
            int rows = arr.length, cols = arr[0].length;
            float[] result = new float[rows * cols];
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++)
                    result[i * cols + j] = arr[i][j];
            return result;
        }
        double[] d = flattenToDouble(value);
        float[] result = new float[d.length];
        for (int i = 0; i < d.length; i++) result[i] = (float) d[i];
        return result;
    }

    public static long[] flattenToLong(Object value) {
        if (value instanceof long[] arr) return arr;
        if (value instanceof long[][] arr) {
            int rows = arr.length, cols = arr[0].length;
            long[] result = new long[rows * cols];
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++)
                    result[i * cols + j] = arr[i][j];
            return result;
        }
        double[] d = flattenToDouble(value);
        long[] result = new long[d.length];
        for (int i = 0; i < d.length; i++) result[i] = (long) d[i];
        return result;
    }

    public static int[] flattenToInt(Object value) {
        if (value instanceof int[] arr) return arr;
        if (value instanceof int[][] arr) {
            int rows = arr.length, cols = arr[0].length;
            int[] result = new int[rows * cols];
            for (int i = 0; i < rows; i++)
                for (int j = 0; j < cols; j++)
                    result[i * cols + j] = arr[i][j];
            return result;
        }
        double[] d = flattenToDouble(value);
        int[] result = new int[d.length];
        for (int i = 0; i < d.length; i++) result[i] = (int) d[i];
        return result;
    }

    private static double[] flatten2DDouble(double[][] arr) {
        int rows = arr.length, cols = arr[0].length;
        double[] result = new double[rows * cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                result[i * cols + j] = arr[i][j];
        return result;
    }

    public static long[] inferShape(Object value) {
        if (value instanceof double[][] arr) return new long[]{arr.length, arr[0].length};
        if (value instanceof float[][] arr) return new long[]{arr.length, arr[0].length};
        if (value instanceof int[][] arr) return new long[]{arr.length, arr[0].length};
        if (value instanceof long[][] arr) return new long[]{arr.length, arr[0].length};
        if (value instanceof String[][] arr) return new long[]{arr.length, arr[0].length};
        if (value instanceof double[] arr) return new long[]{arr.length};
        if (value instanceof float[] arr) return new long[]{arr.length};
        if (value instanceof int[] arr) return new long[]{arr.length};
        if (value instanceof long[] arr) return new long[]{arr.length};
        if (value instanceof String[] arr) return new long[]{arr.length};
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return new long[]{0};
            Object first = list.get(0);
            if (first instanceof List<?>) {
                long[] inner = inferShape(first);
                long[] result = new long[1 + inner.length];
                result[0] = list.size();
                System.arraycopy(inner, 0, result, 1, inner.length);
                return result;
            }
            return new long[]{list.size()};
        }
        throw new BadRequestException("无法推断 shape: " + value.getClass().getName());
    }

    public static String[] flattenToString(Object value) {
        if (value instanceof String[] arr) return arr;
        if (value instanceof String[][] arr) {
            int total = 0;
            for (String[] row : arr) total += row.length;
            String[] result = new String[total];
            int offset = 0;
            for (String[] row : arr) {
                System.arraycopy(row, 0, result, offset, row.length);
                offset += row.length;
            }
            return result;
        }
        if (value instanceof List<?> list) {
            String[] result = new String[countElements(value)];
            fillStrings(value, result, new int[]{0});
            return result;
        }
        throw new BadRequestException("无法展平为 String[]: " + value.getClass().getName());
    }

    private static void fillStrings(Object value, String[] out, int[] offset) {
        if (value instanceof List<?> list) {
            for (Object item : list) fillStrings(item, out, offset);
        } else if (value instanceof String[] arr) {
            System.arraycopy(arr, 0, out, offset[0], arr.length);
            offset[0] += arr.length;
        } else if (value instanceof String s) {
            out[offset[0]++] = s;
        } else {
            throw new BadRequestException("字符串展平遇到非字符串元素: " + value);
        }
    }

    public static int countElements(Object value) {
        if (value instanceof List<?> list) {
            int count = 0;
            for (Object item : list) count += countElements(item);
            return count;
        }
        if (value instanceof Object[] arr) return arr.length;
        if (value instanceof double[] arr) return arr.length;
        if (value instanceof float[] arr) return arr.length;
        if (value instanceof int[] arr) return arr.length;
        if (value instanceof long[] arr) return arr.length;
        if (value instanceof Number) return 1;
        if (value instanceof String s) return 1;
        throw new BadRequestException("无法计算元素数: " + value.getClass().getName());
    }
}
