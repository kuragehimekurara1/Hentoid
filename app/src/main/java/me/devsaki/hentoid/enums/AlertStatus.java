package me.devsaki.hentoid.enums;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;

import me.devsaki.hentoid.R;

/**
 * Created by Robb on 2019/11
 */
public enum AlertStatus {

    ORANGE(R.color.orange, R.drawable.ic_exclamation),
    RED(R.color.red, R.drawable.ic_error),
    BLACK(R.color.black, R.drawable.ic_nuclear),
    NONE(R.color.white, R.drawable.ic_info);

    private final int color;
    private final int icon;

    AlertStatus(@ColorRes int color, @DrawableRes int icon) {
        this.color = color;
        this.icon = icon;
    }

    public @ColorRes
    int getColor() {
        return color;
    }

    public @ColorRes
    int getIcon() {
        return icon;
    }
}
