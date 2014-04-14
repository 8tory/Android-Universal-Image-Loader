/*******************************************************************************
 * Copyright 2011-2013 Sergey Tarasevich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.core.decode;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.MediaStore.Images.ImageColumns;
import android.text.TextUtils;
import android.support.v4.util.LruCache;

import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.download.ImageDownloader.Scheme;
import com.nostra13.universalimageloader.utils.L;

import java.io.IOException;
import java.io.InputStream;

/**
 * Initialization:
 *
 * ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(getApplicationContext())
 *             ...
 *             .imageDecoder(new ContentImageDecoder(getApplicationContext()))
 *             .build();
 *
 * Credit:
 *   [Daniel Gabriel] (http://stackoverflow.com/questions/20931585/is-it-possible-to-display-video-thumbnails-using-universal-image-loader-and-how)
 */
public class ContentImageDecoder extends BaseImageDecoder {
    public static final String TAG = "ContentImageDecoder";

    private final Context mContext;
    private ContentResolver mContentResolver;
    private int mResourceId;

    public static final int K = 1024;
    private static LruCache<String, Integer> sRotationCache;
    private static LruCache<String, Boolean> sVideoCache;

    private static final boolean DEBUG = false;
    private static class Log8 {
        public static int d(Object... arr) {
            if (!DEBUG) return 0;
            StackTraceElement call = Thread.currentThread().getStackTrace()[3];
            String className = call.getClassName();
            className = className.substring(className.lastIndexOf('.') + 1);
            return android.util.Log.d(TAG,
                className + "."
                + call.getMethodName() + ":"
                + call.getLineNumber() + ": "
                + java.util.Arrays.deepToString(arr));
        }
    }

    public ContentImageDecoder(Context context) {
        this(false, context, 0);
    }

    public ContentImageDecoder(Context context, int resourceId) {
        this(false, context, resourceId);
    }

    public ContentImageDecoder(boolean loggingEnabled, Context context, int resourceId) {
        super(false);
        mContext = context;
        mResourceId = resourceId;
    }

    private ContentResolver getContentResolver() {
        if (mContentResolver == null) {
            mContentResolver = mContext.getContentResolver();
        }
        Log8.d(mContentResolver);
        return mContentResolver;
    }

    @Override
    public Bitmap decode(ImageDecodingInfo decodingInfo) throws IOException {
        String imageUri = decodingInfo.getImageUri();
        Uri uri = Uri.parse(imageUri);

        if (isVideoUri(uri)) {
            int width = decodingInfo.getTargetSize().getWidth();
            int height = decodingInfo.getTargetSize().getHeight();
            Log8.d(width, height);
            Bitmap decodedBitmap = makeVideoThumbnailFromMediaMetadataRetriever(
                    getMediaMetadataRetriever(mContext, uri));
            /*
            decodedBitmap = makeVideoThumbnailFromMediaMetadataRetriever(
                    getMediaMetadataRetriever(getVideoFilePath(uri)));
            */
            Log8.d(decodedBitmap);
            if (decodedBitmap == null) {
                Log8.d(getVideoFilePath(uri));
                decodedBitmap = makeVideoThumbnailFromMediaStore(getVideoFilePath(uri));
                Log8.d(decodedBitmap);
            }

            if (decodedBitmap == null) {
                L.e(ERROR_CANT_DECODE_IMAGE, decodingInfo.getImageKey());
            }
            else {
                Log8.d(decodedBitmap.getWidth(), decodedBitmap.getHeight());
                ExifInfo exif = new ExifInfo();
                ImageFileInfo imageInfo = new ImageFileInfo(new ImageSize(width, height, exif.rotation), exif);
                decodedBitmap = considerExactScaleAndOrientaiton(decodedBitmap, decodingInfo, imageInfo.exif.rotation,
                        imageInfo.exif.flipHorizontal);
                Log8.d(decodedBitmap.getWidth(), decodedBitmap.getHeight());
                if (mResourceId > 0) overlayCenter(decodedBitmap, mContext, mResourceId);
                Log8.d(decodedBitmap.getWidth(), decodedBitmap.getHeight());
            }

            return decodedBitmap;
        }
        else {
            return super.decode(decodingInfo);
        }
    }

    /*
    public static Bitmap loadBitmapFromView(View v, int width, int height) {
        width = v.getLayoutParams().width;
        height = v.getLayoutParams().height;
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        v.setDrawingCacheEnabled(false);
        Canvas c = new Canvas(b);
        v.layout(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
        v.draw(c);
        return b;
    }

    protected static Bitmap overlayLeft(Bitmap bitmap, Context context, int resourceId) {
        if (resourceId <= 0) return bitmap;
        LayoutInflater inflater = LayoutInflater.from(context);
        View layout = inflater.inflate(R.layout.video, null);
        layout.setDrawingCacheEnabled(true);
        ImageView image = (ImageView) layout.findViewById(R.id.image);
        image.setImageBitmap(bitmap);
        ImageView overlay = (ImageView) layout.findViewById(R.id.overlay);
        overlay.setImageResource(resourceId);
        Bitmap b = loadBitmapFromView(layout, bitmap.getWidth(), bitmap.getHeight());

        //bitmap.recycle();
        //bitmap = null;
        return b;
    }
    */

    protected static void overlayLeft(Bitmap bitmap, Context context, int resourceId) {
        if (resourceId <= 0) return;
        Bitmap overlay = BitmapFactory.decodeResource(context.getResources(),
                resourceId);

        float scale = (float) bitmap.getHeight() / overlay.getHeight();
        int w = Math.round(scale * overlay.getWidth()) / 2;
        int h = Math.round(scale * overlay.getHeight()) / 2;
        Bitmap scaledOverlay = scaleBitmap(overlay, w, h);

        final Rect rect = new Rect(0, 0, w, h);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(scaledOverlay, null, rect, null);
        canvas.drawBitmap(scaledOverlay, null, new Rect(0, h, w, h * 2), null);

        overlay.recycle();
        overlay = null;
        scaledOverlay.recycle();
        scaledOverlay = null;
    }

    protected static void overlayCenter(Bitmap bitmap, Context context, int resourceId) {
        if (resourceId <= 0) return;
        Bitmap overlay = BitmapFactory.decodeResource(context.getResources(),
                resourceId);

        final int x = bitmap.getWidth() / 2;
        final int y = bitmap.getHeight() / 2;

        final int w;
        final int h;
        if (bitmap.getWidth() >= bitmap.getHeight()) {
            h = bitmap.getHeight() / 5;
            w = overlay.getWidth() * h / overlay.getHeight();
        } else {
            w = bitmap.getWidth() / 5;
            h = overlay.getHeight() * w / overlay.getWidth();
        }

        final Rect rect = new Rect(x - w / 2, y - h / 2, x + w / 2, y + h / 2);

        final Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(overlay, null, rect, null);

        overlay.recycle();
        overlay = null;
    }

    private Bitmap makeVideoThumbnailFromMediaStore(String filePath) {
        if (TextUtils.isEmpty(filePath)) return null;
        Bitmap thumbnail = ThumbnailUtils.createVideoThumbnail(filePath, MediaStore.Video.Thumbnails.MINI_KIND);
        if (thumbnail != null) {
            Log8.d(thumbnail.getWidth(), thumbnail.getHeight());
        }
        return thumbnail;
    }

    private Bitmap makeVideoThumbnailFromMediaMetadataRetriever(MediaMetadataRetriever retriever) {
        if (retriever == null) return null;

        Bitmap thumbnail = null;
        byte[] picture = retriever.getEmbeddedPicture();

        if (picture != null) {
            Log8.d();
            thumbnail = BitmapFactory.decodeByteArray(picture, 0, picture.length);
            if (thumbnail != null) {
                Log8.d(thumbnail.getWidth(), thumbnail.getHeight());
            }
        } else {
            Log8.d();
        }

        if (thumbnail == null) {
            Log8.d();
            thumbnail = retriever.getFrameAtTime();
            if (thumbnail != null) {
                Log8.d(thumbnail.getWidth(), thumbnail.getHeight());
            }
        }

        if (thumbnail == null) {
            Log8.d();
            return null;
        }

        return thumbnail;
    }

    private static MediaMetadataRetriever getMediaMetadataRetriever(String filePath) {
        if (TextUtils.isEmpty(filePath)) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(filePath);
        return retriever;
    }

    private static MediaMetadataRetriever getMediaMetadataRetriever(Context context, Uri uri) {
        if (context == null || uri == null) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        retriever.setDataSource(context, uri);
        return retriever;
    }

    private boolean isVideoUri(Uri uri) {
        Boolean isVideo = getVideoCache().get(uri.toString());
        if (isVideo == null) {
            String type = getContentResolver().getType(uri);
            isVideo = new Boolean(!TextUtils.isEmpty(type) && type.startsWith("video/"));
            getVideoCache().put(uri.toString(), isVideo);
        }
        return isVideo;
    }

    private String getVideoFilePath(Uri uri) {
        String columnName = MediaStore.Video.VideoColumns.DATA;
        Cursor cursor = getContentResolver().query(uri, new String[] { columnName }, null, null, null);
        try {
            int dataIndex = cursor.getColumnIndex(columnName);
            if (dataIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(dataIndex);
            }
        }
        finally {
            cursor.close();
        }
        return null;
    }

    private static Bitmap scaleBitmap(Bitmap origBitmap, int width, int height) {
        float scale = Math.min(
                ((float)width) / ((float)origBitmap.getWidth()),
                ((float)height) / ((float)origBitmap.getHeight())
        );
        return Bitmap.createScaledBitmap(origBitmap,
                (int)(((float)origBitmap.getWidth()) * scale),
                (int)(((float)origBitmap.getHeight()) * scale),
                false
        );
    }

    private String cleanUriString(String contentUriWithAppendedSize) {
        // replace the size at the end of the URI with an empty string.
        // the URI will be in the form "content://....._256x256
        return contentUriWithAppendedSize.replaceFirst("_\\d+x\\d+$", "");
    }

    private static LruCache<String, Integer> getRotationCache() {
        if (sRotationCache == null) {
            sRotationCache = new LruCache<String, Integer>(16 * K);
        }
        return sRotationCache;
    }

    private static LruCache<String, Boolean> getVideoCache() {
        if (sVideoCache == null) {
            sVideoCache = new LruCache<String, Boolean>(16 * K);
        }
        return sVideoCache;
    }

    @Override
    protected ImageFileInfo defineImageSizeAndRotation(InputStream imageStream, ImageDecodingInfo decodingInfo) throws IOException {
        String imageUri = decodingInfo.getImageUri();

        if (Scheme.ofUri(imageUri) == Scheme.FILE || android.os.Build.VERSION.SDK_INT <= 10) {
            return super.defineImageSizeAndRotation(imageStream, decodingInfo);
        }
        Options options = new Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(imageStream, null, options);

        ExifInfo exif = getExifInfo(imageUri, decodingInfo, null);

        return new ImageFileInfo(new ImageSize(options.outWidth, options.outHeight, exif.rotation), exif);
    }

    protected ExifInfo getExifInfo(String uri, ImageDecodingInfo decodingInfo, Object extra) {
        ExifInfo exif;
        Integer rotation = getRotationCache().get(uri);
        if (rotation == null) {
            if (decodingInfo.shouldConsiderExifParams()) {
                exif = getExifInfo(uri, extra);
                getRotationCache().put(uri, exif.rotation);
            } else {
                exif = new ExifInfo();
            }
        } else {
            exif = new ExifInfo(rotation, false);
        }
        return exif;
    }

    protected ExifInfo getExifInfo(String imageUri, Object extra) {
        switch (Scheme.ofUri(imageUri)) {
            case FILE:
                return getExifInfoFromFile(imageUri, extra);
            case CONTENT:
                return getExifInfoFromContent(imageUri, extra);
            default:
                return new ExifInfo();
        }
    }

    protected ExifInfo getExifInfoFromContent(String imageUri, Object extra) {
        int rotation = 0;
        boolean flip = false;
        final String[] PROJECTION = {
            ImageColumns.ORIENTATION
        };
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(Uri.parse(imageUri), PROJECTION, null, null, null);
        } catch (Exception e) {
        }
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int orientation = cursor.getInt(cursor.getColumnIndexOrThrow((ImageColumns.ORIENTATION)));
                    rotation = (orientation + 360) % 360;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                cursor.close();
            }
        }
        return new ExifInfo(rotation, flip);
    }

    protected ExifInfo getExifInfoFromFile(String imageUri, Object extra) {
        return defineExifOrientation(imageUri);
    }
}
