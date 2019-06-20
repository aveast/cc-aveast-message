package cc.aveast.message.fixed;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(RUNTIME) @Target(FIELD)
public @interface FixedElement {

    /**
     * 字段长度
     * @return
     */
    int value() default 0;

    boolean number() default false;

    /**
     * 为空返回，不但本字段不填充，剩下字段也不填充
     * 仅用在创建报文
     * @return
     */
    boolean nullreturn() default false;

    /**
     * 条件，标志当前字段为后续字段的条件
     * @return
     */
    int condition() default 0;

    /**
     * 使用条件编号，0不使用
     * @return
     */
    int use() default 0;

    /**
     * 条件值，只有当条件为该值时，才处理该字段
     * 与user配套使用
     * 支持多指，以“,”进行分割
     * @return
     */
    String key() default "";

    /**
     * 是否hex字符串，如果是，解析取字段长度*2
     * @return
     */
    boolean hex() default false;

    /**
     * 是否进行GBK转换
     * @return
     */
    boolean gbk() default false;
}
