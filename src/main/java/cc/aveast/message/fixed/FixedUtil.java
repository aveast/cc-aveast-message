package cc.aveast.message.fixed;

import cc.aveast.common.Charset;
import cc.aveast.tool.object.ReflectUtil;
import cc.aveast.tool.string.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.charset.UnsupportedCharsetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FixedUtil {

    private static final Logger logger = LoggerFactory.getLogger(FixedUtil.class);

    /**
     * 定长报文反序列化
     * @param message 定常报文
     * @param object 目标对象
     * @return 未处理的剩余报文
     */
    public static String deserialize(String message, Object object) {
        return deserialize(message.getBytes(), object);
    }

    /**
     * 定长报文反序列化
     * @param message 定长报文
     * @param object 目标对象
     * @return 未处理的剩余报文
     */
    public static String deserialize(byte[] message, Object object) {
        ByteArrayInputStream byteArrayInputStream = deserialize(new ByteArrayInputStream(message), object.getClass(), object);
        return byteArrayInputStream.toString();
    }

    /**
     * 定长报文反序列化
     * @param message 定长报文
     * @param clazz 目标对象类型
     * @param object 目标对象
     * @return 未转换的部分报文
     */
    public static ByteArrayInputStream deserialize(ByteArrayInputStream message, Class clazz, Object object) {
        Map<Integer, String> condition = null;
        ByteArrayInputStream var = message;

        // 获取全部成员变量
        Field[] fields = clazz.getDeclaredFields();

        // 获取父类
        Class father = clazz.getSuperclass();
        if (father != null && !father.toString().endsWith("Object")) {
            // 存在父类，先对父类进行转换
            var = deserialize(message, father, object);
        }

        int retCode;
        String bField = "0";

        for (Field curField : fields) {
            // 获取当前变量名
            String key;
            key = curField.getName();

             // 获取注释
            FixedElement fixedElement = curField.getAnnotation(FixedElement.class);
            if (fixedElement == null &&
                    !curField.getType().isAssignableFrom(List.class)) {
                // 对于没加FixedElement注解且非List对象的，不处理
                continue;
            }

            // 设置变量可访问
            curField.setAccessible(true);

            if (curField.getType().isAssignableFrom(List.class)) {
                // 对列表类型进行处理
                Class listChildClass = ReflectUtil.getListParameterizedType(curField);

                Object listObject;
                try {
                    listObject = curField.get(object);
                } catch (IllegalAccessException e) {
                    logger.error("成员变量[{}]无法访问", curField.getName());
                    return null;
                }

                // 从前一个成员变量获取列表个数
                int count = Integer.parseInt(bField);
                var = deserializeArray(var, count, listChildClass, (List<Object>) listObject);
                if (var == null) {
                    logger.error("反序列化List成员变量[{}]失败", curField.getName());
                    return null;
                }
            }

            // 长度小于1的不处理
            if (fixedElement.value() < 1) {
                curField.setAccessible(false);
                continue;
            }

            // 判断是否满足条件，不满足不解析
            if (!needOperate(condition, fixedElement)) {
                curField.setAccessible(false);
                continue;
            }

            byte[] field;
            if (fixedElement.hex()) {
                // 成员变量对应字符串为hex格式
                field = new byte[fixedElement.value() * 2];
                try {
                    var.read(field);
                } catch (IOException e) {
                    logger.error("读取成员变量[{}]的序列值失败", curField.getName());
                    return null;
                }
                field = StringUtil.hex2bytes(new String(field));
            } else {
                field = new byte[fixedElement.value()];
                try {
                    var.read(field);
                } catch (IOException e) {
                    logger.error("读取成员变量[{}]的序列值失败", curField.getName());
                    return null;
                }
            }

            if (fixedElement.gbk()) {
                try {
                    field = new String(field, Charset.GBK.getStandardCode()).getBytes();
                } catch (UnsupportedEncodingException e) {
                    logger.error("字符类型[{}]不支持", Charset.GBK.getStandardCode());
                    return null;
                }
            }

            retCode = setValue(curField, field, object);
            if (retCode != 0) {
                logger.error("处理[{}]成员变量时失败", key);
                return null;
            }

            if (fixedElement.condition() > 0) {
                if (condition == null) {
                    condition = new LinkedHashMap<>();
                }

                condition.put(fixedElement.condition(), new String(field));
            }

            bField = new String(field);

            // 设置对象不可访问
            curField.setAccessible(false);
        }

        return var;
    }

    /**
     * 判断当前字段是否满足处理条件（成员变量被condition标注）
     * @param condition 条件
     * @param fixedElement 注释
     * @return 是/否
     */
    private static boolean needOperate(Map<Integer, String> condition, FixedElement fixedElement) {
        int userKey = fixedElement.use();
        if (userKey == 0) {
            return true;
        }

        if (condition == null || condition.isEmpty()) {
            return false;
        }

        String value = condition.get(userKey);
        if (StringUtil.isEmpty(value)) {
            return false;
        }

        if (fixedElement.key().equals("*")) {
            return true;
        }

        String[] valuePer = fixedElement.key().split(",");
        for (String cur : valuePer) {
            if (value.equals(cur)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 反序列化List数据对象
     * @param message 定长报文
     * @param count 成员数量
     * @param clazz List泛型类型
     * @param tagList 目标反序列还对象
     * @return 未反序列化报文
     */
    private static ByteArrayInputStream deserializeArray(ByteArrayInputStream message, int count, Class clazz, List tagList) {
        ByteArrayInputStream var = message;

        for (int i = 0; i < count; i++) {
            Object tmpObject;
            try {
                tmpObject = clazz.newInstance();
            } catch (Exception e) {
                logger.error("创建[{}]类对象失败", clazz.getSimpleName());
                return null;
            }

            var = deserialize(var, clazz, tmpObject);
            if (var == null) {
                logger.error("创建第[{}]个List对象时失败", i);
                return null;
            }

            tagList.add(tmpObject);
        }

        return var;
    }

    /**
     * 对象序列化成定长报文
     * @param clazz 对象类型
     * @param object 待序列化对象
     * @return 序列化报文
     */
    public static byte[] serialize(Class clazz, Object object) {
        Field[] fields = clazz.getDeclaredFields();

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        Class father = clazz.getSuperclass();

        if (father != null && !father.toString().endsWith("Object")) {
            // 先对父类进行转换
            try {
                byteArrayOutputStream.write(serialize(father, object));
            } catch (IOException e) {
                logger.error("序列化父类[{}]失败", father.getSimpleName());
                return null;
            }
        }

        for (Field curField : fields) {
            // 获取当前成员变量
            String key;
            key = curField.getName();

            // 设置变量可访问
            curField.setAccessible(true);

                FixedElement fixedElement = curField.getAnnotation(FixedElement.class);
                if (fixedElement == null && !curField.getType().isAssignableFrom(List.class)) {
                    // 对于没加FieldLength注解且非List对象的，不处理
                    continue;
                }

                String tmp = getValue(curField, object);
                if (tmp == null) {
                    tmp = "";
                }

                if (tmp.trim().length() == 0 && fixedElement.nullreturn()) {
                    break;
                }

                int tmpLength = tmp.getBytes().length;
                if (fixedElement == null || tmpLength == fixedElement.value()) {
                    byteArrayOutputStream.write(tmp.getBytes());
                } else if (tmpLength < fixedElement.value()) {
                    if (fixedElement.number()) {
                        if (StringUtil.isEmpty(tmp)) {
                            tmp = "0";
                        }
                        byteArrayOutputStream.write(String.format("%0" + fixedElement.value() + "d", Integer.parseInt(tmp)).getBytes());
                    } else {
                        String writeStr = new StringBuffer(tmp)
                                .append(String.format("%" + (fixedElement.value() - tmpLength) + "s", " "))
                                .toString();

                        if (fixedElement.hex()) {
                            byteArrayOutputStream.write(StringUtil.bytes2hex(writeStr.getBytes()).getBytes());
                        } else {
                            byteArrayOutputStream.write(writeStr.getBytes());
                        }
                    }
                } else {
                    byte[] writeByte;
                    if (fixedElement.hex()) {
                        writeByte = StringUtil.string2hex(tmp).getBytes();
                        byteArrayOutputStream.write(writeByte, 0, fixedElement.value() * 2);
                    } else {
                        byteArrayOutputStream.write(tmp.getBytes(), 0, fixedElement.value());
                    }
                }

            }

        return byteArrayOutputStream.toByteArray();
    }

    /**
     * 将List型对象转换成定长
     *
     * @param object list型对象
     * @return 定长报文
     */
    private static byte[] toByte(List<Object> object) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        if (object == null || object.size() < 1) {
            return byteArrayOutputStream.toByteArray();
        }

        for (Object curObj : object) {
            byte[] tmp = toByte(curObj.getClass(), curObj);
            try {
                byteArrayOutputStream.write(tmp);
            } catch (IOException e) {
                logger.error("列表对象转换定长捕获异常", e);
                return null;
            }
        }

        return byteArrayOutputStream.toByteArray();
    }

    private static int setValue(Field curField, byte[] field, Object curObject) {
        Class fieldType;
        int retCode;

        if (field.length == 0) {
            logger.warn("当前字段长度为0，不处理");
            return 0;
        }

        fieldType = curField.getType();

        // 基本类型
        if (fieldType.isPrimitive()) {
            return setPrimitiveValue(fieldType, curField, field, curObject);
        }

        // 枚举类
        if (fieldType.isEnum()) {
            logger.error("反转不支持枚举");
            return -1;
        }

        if (fieldType.isAssignableFrom(String.class)) {
            // 成员变量 - String类型
            return setStringValue(curField, field, curObject);
        } else if (Number.class.isAssignableFrom(fieldType)) {
            // 成员变量 - Number类型（基本类型的类）
            return setPrimitiveValue(fieldType, curField, field, curObject);
        } else {
            logger.error("[{}]类型的成员变量[{}]暂不支持",
                    fieldType.getClass().getSimpleName(), curField.getName());
            return -1;
        }
    }

    private static int setStringValue(Field curField, byte[] field, Object curObject) {
        try {
            curField.set(curObject, new String(field).trim());
        } catch (IllegalAccessException e) {
            logger.error("将[{}]类型成员变量[{}]赋值[{}]失败", curField.getType().getSimpleName()
                    , curField.getName(), new String(field));
            return -1;
        }

        return 0;
    }

    /**
     * 对基本类成员变量进行赋值
     *
     * @param fieldType 成员变量类型
     * @param curField  成员变量
     * @param field     字段值
     * @param curObject 当前类
     * @return -1 失败
     */
    private static int setPrimitiveValue(Class fieldType, Field curField, byte[] field, Object curObject) {
        String type = fieldType.toString();

        try {
            if (type.equals("long")) {
                curField.set(curObject, Long.parseLong(new String(field)));
            } else if (type.equals("int")) {
                curField.set(curObject, Integer.parseInt(new String(field)));
            } else if (type.equals("double")) {
                curField.set(curObject, Double.parseDouble(new String(field)));
            } else if (type.equals("float")) {
                curField.set(curObject, Float.parseFloat(new String(field)));
            } else if (type.equals("boolean")) {
                curField.set(curObject, Boolean.parseBoolean(new String(field)));
            } else {
                curField.set(curObject, new String(field));
            }
        } catch (IllegalAccessException e) {
            logger.error("将[{}]设置进[{}]类型失败", new String(field), type);
            return -1;
        }

        return 0;
    }

    private static String getValue(Field field, Object object) {
        Class type = field.getType();

        try {
            // 基础类型
            if (type.isPrimitive()) {
                return ReflectUtil.getPrimitiveValue(type, field, object);
            }

            // 枚举
            if (type.isEnum()) {
                Enum enumEl = (Enum) field.get(object);
                return enumEl.name();
            }

            if (type.isAssignableFrom(String.class)) {
                // String类
                return (String) field.get(object);
            } else if (Number.class.isAssignableFrom(type)) {
                // 数字类
                Number number = (Number) field.get(object);
                if (number == null) {
                    return null;
                }
                return number.toString();
            } else if (type.isAssignableFrom(List.class)) {
                Object tmp = field.get(object);
                byte[] tmpByte = toByte((List<Object>) tmp);
                if (tmpByte == null) {
                    return null;
                }
                return new String(tmpByte);
            } else {
                Object tmp = field.get(object);
                byte[] tmpByte = toByte(tmp.getClass(), tmp);
                if (tmpByte == null) {
                    return null;
                }

                return new String(tmpByte);
            }
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
