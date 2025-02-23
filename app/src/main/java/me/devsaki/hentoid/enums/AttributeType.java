package me.devsaki.hentoid.enums;

import androidx.annotation.StringRes;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

import javax.annotation.Nullable;

import io.objectbox.converter.PropertyConverter;
import me.devsaki.hentoid.R;

/**
 * Attribute Type enumerator
 */
public enum AttributeType {

    // Attributes stored in Attributes table of the DB
    ARTIST(0, R.string.object_artist, R.drawable.ic_attribute_artist),
    PUBLISHER(1, R.string.object_publisher, R.drawable.ic_site_fakku),
    LANGUAGE(2, R.string.object_language, R.drawable.ic_attribute_language),
    TAG(3, R.string.object_tag, R.drawable.ic_attribute_tag),
    TRANSLATOR(4, R.string.object_translator, R.drawable.ic_site_fakku),
    SERIE(5, R.string.object_series, R.drawable.ic_attribute_serie),
    UPLOADER(6, R.string.object_uploader, R.drawable.ic_site_fakku),
    CIRCLE(7, R.string.object_circle, R.drawable.ic_site_fakku),
    CHARACTER(8, R.string.object_character, R.drawable.ic_attribute_character),
    CATEGORY(9, R.string.object_category, R.drawable.ic_site_fakku),
    // Attributes displayed on screen and stored elsewhere
    SOURCE(10, R.string.object_source, R.drawable.ic_attribute_source);

    private final int code;
    private final @StringRes
    int displayName;
    private final int icon;

    AttributeType(int code, @StringRes int displayName, int icon) {
        this.code = code;
        this.displayName = displayName;
        this.icon = icon;
    }

    @Nullable
    public static AttributeType searchByCode(int code) {
        for (AttributeType s : AttributeType.values()) {
            if (s.getCode() == code) {
                return s;
            }
        }

        return null;
    }

    @Nullable
    public static AttributeType searchByName(String name) {
        for (AttributeType s : AttributeType.values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }

        return null;
    }

    public int getCode() {
        return code;
    }

    public int getIcon() {
        return icon;
    }

    public @StringRes
    int getDisplayName() {
        return displayName;
    }


    public static class AttributeTypeAdapter {
        @ToJson
        String toJson(AttributeType attrType) {
            return attrType.name();
        }

        @FromJson
        AttributeType fromJson(String name) {
            AttributeType attrType = AttributeType.searchByName(name);
            if (null == attrType && name.equalsIgnoreCase("series"))
                attrType = SERIE; // Fix the issue with v1.6.5
            return attrType;
        }
    }

    public static class AttributeTypeConverter implements PropertyConverter<AttributeType, Integer> {
        @Override
        public AttributeType convertToEntityProperty(Integer databaseValue) {
            if (databaseValue == null) {
                return null;
            }
            for (AttributeType type : AttributeType.values()) {
                if (type.getCode() == databaseValue) {
                    return type;
                }
            }
            return AttributeType.TAG;
        }

        @Override
        public Integer convertToDatabaseValue(AttributeType entityProperty) {
            return entityProperty == null ? null : entityProperty.getCode();
        }
    }
}
