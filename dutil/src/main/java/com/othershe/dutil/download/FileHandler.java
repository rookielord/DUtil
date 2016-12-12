package com.othershe.dutil.download;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.othershe.dutil.Utils.Utils;
import com.othershe.dutil.data.Ranges;
import com.othershe.dutil.net.OkHttpManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import static com.othershe.dutil.data.Consts.ON_CANCEL;
import static com.othershe.dutil.data.Consts.ON_PROGRESS;
import static com.othershe.dutil.data.Consts.ON_START;
import static java.nio.channels.FileChannel.MapMode.READ_WRITE;

public class FileHandler {

    private int EACH_TEMP_SIZE = 16; //long + long = 8 + 8
    private int THREAD_COUNT;
    private int TEMP_FILE_TOTAL_SIZE;

    private boolean IS_PAUSE = false; //是否暂停
    private boolean IS_CANCEL = false; //是否取消

    private String path;
    private String name;
    private String url;
    private Handler handler;

    public FileHandler(String url, String path, String name, int threadCount, Handler handler) {
        THREAD_COUNT = threadCount == 0 ? 3 : threadCount;
        TEMP_FILE_TOTAL_SIZE = EACH_TEMP_SIZE * THREAD_COUNT;

        this.url = url;
        this.path = path;
        this.name = name;
        this.handler = handler;
    }

    private void onStart(long fileLength) {
        Message message = Message.obtain();
        message.what = ON_START;
        message.arg1 = (int) fileLength;
        handler.sendMessage(message);
    }

    public void onProgress(int length) {
        Message message = Message.obtain();
        message.what = ON_PROGRESS;
        message.arg1 = length;
        handler.sendMessage(message);
    }

    public void onPause() {
        IS_PAUSE = true;
    }

    public void onResume() {
        IS_PAUSE = false;
    }

    public void onCancel() {
        IS_CANCEL = true;
    }

    public void onRestart() {
        IS_CANCEL = false;
    }

    public void onError(String msg) {
        Message message = Message.obtain();
        message.what = ON_START;
        message.obj = msg;
        handler.sendMessage(message);
    }

    /**
     * 准备断点下载
     *
     * @param response
     */
    private void prepareRangeFile(Response response) {
        RandomAccessFile saveRandomAccessFile = null;
        RandomAccessFile tempRandomAccessFile = null;
        FileChannel tempChannel = null;
        long fileLength = response.body().contentLength();

        onStart(fileLength);

        Log.e("tag", fileLength + "");

        try {

            File saveFile = new File(path, name);
            File tempFile = new File(path, name + ".temp");

            if (saveFile.exists() && tempFile.exists()) {
                return;
            } else {
                Utils.deleteFile(saveFile, tempFile);
            }

            saveRandomAccessFile = new RandomAccessFile(saveFile, "rws");
            saveRandomAccessFile.setLength(fileLength);

            tempRandomAccessFile = new RandomAccessFile(tempFile, "rws");
            tempRandomAccessFile.setLength(TEMP_FILE_TOTAL_SIZE);
            tempChannel = tempRandomAccessFile.getChannel();
            MappedByteBuffer buffer = tempChannel.map(READ_WRITE, 0, TEMP_FILE_TOTAL_SIZE);

            long start;
            long end;
            int eachSize = (int) (fileLength / THREAD_COUNT);
            for (int i = 0; i < THREAD_COUNT; i++) {
                if (i == THREAD_COUNT - 1) {
                    start = i * eachSize;
                    end = fileLength - 1;
                } else {
                    start = i * eachSize;
                    end = (i + 1) * eachSize - 1;
                }
                buffer.putLong(start);
                buffer.putLong(end);
            }
        } catch (Exception e) {
            onError(e.toString());
        } finally {
            Utils.close(saveRandomAccessFile);
            Utils.close(tempRandomAccessFile);
            Utils.close(tempChannel);
        }
    }

    /**
     * 开始断点下载
     *
     * @param response
     */
    public void saveRangeFile(Response response) {
        prepareRangeFile(response);

        final File saveFile = new File(path, name);
        final File tempFile = new File(path, name + ".temp");

        final Ranges range = readDownloadRange(tempFile);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int tempI = i;
            OkHttpManager.getInstance().initRequest(url, range.start[i], range.end[i], new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    onError(e.toString());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    startSaveRangeFile(response, tempI, range, saveFile, tempFile);
                }
            });
        }
    }

    private void startSaveRangeFile(Response response, int index, Ranges range, File saveFile, File tempFile) {
        RandomAccessFile saveRandomAccessFile = null;
        FileChannel saveChannel = null;
        InputStream inputStream = null;

        RandomAccessFile tempRandomAccessFile = null;
        FileChannel tempChannel = null;

        try {
            Log.e("tag", "saveRangeFile: start" + index);
            Log.e("tag", "saveRangeFile" + response.body().contentLength() + "");
            saveRandomAccessFile = new RandomAccessFile(saveFile, "rws");
            saveChannel = saveRandomAccessFile.getChannel();
            MappedByteBuffer saveBuffer = saveChannel.map(READ_WRITE, range.start[index], range.end[index] - range.start[index] + 1);

            tempRandomAccessFile = new RandomAccessFile(tempFile, "rws");
            tempChannel = tempRandomAccessFile.getChannel();
            MappedByteBuffer tempBuffer = tempChannel.map(READ_WRITE, 0, TEMP_FILE_TOTAL_SIZE);

            inputStream = response.body().byteStream();
            int len;
            byte[] buffer = new byte[8192];

            while (!IS_CANCEL && (len = inputStream.read(buffer)) != -1) {
                saveBuffer.put(buffer, 0, len);
                tempBuffer.putLong(index * EACH_TEMP_SIZE, tempBuffer.get(index * EACH_TEMP_SIZE) + len);

                onProgress(len);

                while (IS_PAUSE) {

                }
            }

            if (IS_CANCEL) {
                handler.sendEmptyMessage(ON_CANCEL);
                Utils.deleteFile(new File(path, name + ".temp"));
                Utils.deleteFile(new File(path, name));
            }

            Log.e("tag", "saveRangeFile: end" + index);
        } catch (Exception e) {
            onError(e.toString());
        } finally {
            Utils.close(saveRandomAccessFile);
            Utils.close(saveChannel);
            Utils.close(inputStream);
            Utils.close(tempRandomAccessFile);
            Utils.close(tempChannel);
        }
    }

    /**
     * 直接下载文件
     *
     * @param response
     */
    public void saveCommonFile(Response response) {
        InputStream inputStream = null;
        OutputStream outputStream = null;

        long fileLength = response.body().contentLength();
        onStart(fileLength);

        try {
            Utils.deleteFile(new File(path, name));
            File file = Utils.createFile(path, name);
            if (file == null) {
                return;
            }

            inputStream = response.body().byteStream();
            outputStream = new FileOutputStream(file);

            int len;
            byte[] buffer = new byte[8192];
            while (!IS_CANCEL && (len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
                onProgress(len);

                while (IS_PAUSE) {

                }
            }

            if (IS_CANCEL) {
                handler.sendEmptyMessage(ON_CANCEL);
                Utils.deleteFile(new File(path, name));
            }
        } catch (Exception e) {
            onError(e.toString());
        } finally {
            Utils.close(inputStream);
            Utils.close(outputStream);
        }
    }

    /**
     * 读取保存的断点信息
     *
     * @param tempFile
     * @return
     */
    public Ranges readDownloadRange(File tempFile) {
        RandomAccessFile record = null;
        FileChannel channel = null;
        try {
            record = new RandomAccessFile(tempFile, "rws");
            channel = record.getChannel();
            MappedByteBuffer buffer = channel.map(READ_WRITE, 0, TEMP_FILE_TOTAL_SIZE);
            long[] startByteArray = new long[THREAD_COUNT];
            long[] endByteArray = new long[THREAD_COUNT];
            for (int i = 0; i < THREAD_COUNT; i++) {
                startByteArray[i] = buffer.getLong();
                endByteArray[i] = buffer.getLong();
            }
            return new Ranges(startByteArray, endByteArray);
        } catch (Exception e) {
            onError(e.toString());
        } finally {
            Utils.close(channel);
            Utils.close(record);
        }
        return null;
    }
}