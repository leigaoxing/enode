package org.enodeframework.common.container;

import org.enodeframework.common.utils.Assert;

/**
 * @author anruence@gmail.com
 */
public class DefaultObjectContainer {

    public static ObjectContainer INSTANCE;

    public static String[] BASE_PACKAGES = {};

    public static <T> T resolve(Class<T> targetClz) {
        Assert.nonNull(INSTANCE, "ObjectContainer can not be null");
        return INSTANCE.resolve(targetClz);
    }
}
