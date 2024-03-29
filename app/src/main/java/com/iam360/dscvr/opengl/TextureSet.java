package com.iam360.dscvr.opengl;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import timber.log.Timber;

/**
 * @author Nilan Marktanner
 * @date 2016-01-14
 */
public abstract class TextureSet {
    protected TextureTarget[] textureTargets;

    public TextureSet() {
        this.textureTargets = new TextureTarget[getTextureSetSize()];
        for (int i = 0; i < getTextureSetSize(); ++i) {
            this.textureTargets[i] = new TextureTarget(i);
        }
    }

    public abstract Bitmap getTexture(int index);
    protected abstract void updateTexture(int index);
    public abstract int getTextureSetSize();

    public TextureTarget getTextureTarget(int index) {
        return textureTargets[index];
    }

    public abstract void reset();

    public class TextureTarget implements Target {
        private int index;
        private Bitmap texture;

        public TextureTarget(int index) {
            this.index = index;
        }

        @Override
        public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
            setTexture(bitmap);
            updateTexture(index);
        }

        @Override
        public void onBitmapFailed(Drawable errorDrawable) {
            Timber.d("Failed to load texture into texture target %s", index);
        }

        @Override
        public void onPrepareLoad(Drawable placeHolderDrawable) {

        }

        public Bitmap getTexture() {
            return texture;
        }

        public void setTexture(Bitmap texture) {
            this.texture = texture;
        }
    }
}
