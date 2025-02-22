package com.arcsoft.arcfacedemo.util.face;

import android.hardware.Camera;
import android.util.Log;

import com.arcsoft.arcfacedemo.model.FacePreviewInfo;
import com.arcsoft.arcfacedemo.util.TrackUtil;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.LivenessInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 人脸操作辅助类
 */
public class FaceHelper {
    /**
     * supportedSize:{"formats":[{"index":1,"type":6,"default":1,"size":["3264x2448","1920x1080","1280x720","640x480","1600x1200","2592x1944","3264x2448"]},{"index":2,"type":4,"default":1,"size":["640x480","320x180","320x240","424x240","640x360"]}]}
     */
    public static final int DEFAULT_PREVIEW_WIDTH = 640;
    public static final int DEFAULT_PREVIEW_HEIGHT = 480;


    private static final String TAG = "FaceHelper";
    /**
     * 线程池正在处理任务
     */
    private static final int ERROR_BUSY = -1;
    /**
     * 特征提取引擎为空
     */
    private static final int ERROR_FR_ENGINE_IS_NULL = -2;
    /**
     * 活体检测引擎为空
     */
    private static final int ERROR_FL_ENGINE_IS_NULL = -3;
    /**
     * 人脸追踪引擎
     */
    private FaceEngine ftEngine;
    /**
     * 特征提取引擎
     */
    private FaceEngine frEngine;
    /**
     * 活体检测引擎
     */
    private FaceEngine flEngine;

    private Camera.Size previewSize;

    private List<FaceInfo> faceInfoList = new ArrayList<>();
    /**
     * 特征提取线程池
     */
    private ExecutorService frExecutor;
    /**
     * 活体检测线程池
     */
    private ExecutorService flExecutor;
    /**
     * 特征提取线程队列
     */
    private LinkedBlockingQueue<Runnable> frThreadQueue = null;
    /**
     * 活体检测线程队列
     */
    private LinkedBlockingQueue<Runnable> flThreadQueue = null;

    private FaceListener faceListener;
    /**
     * 上次应用退出时，记录的该App检测过的人脸数了
     */
    private int trackedFaceCount = 0;
    /**
     * 本次打开引擎后的最大faceId
     */
    private int currentMaxFaceId = 0;

    //    private List<Integer> formerTrackIdList = new ArrayList<>();
    private List<Integer> currentTrackIdList = new ArrayList<>();
    //    private List<FaceInfo> formerFaceInfoList = new ArrayList<>();
    private List<FacePreviewInfo> facePreviewInfoList = new ArrayList<>();
    /**
     * 用于存储人脸对应的姓名，KEY为trackId，VALUE为name
     */
    private ConcurrentHashMap<Integer, String> nameMap = new ConcurrentHashMap<>();

    private FaceHelper(Builder builder) {
        ftEngine = builder.ftEngine;
        faceListener = builder.faceListener;
        trackedFaceCount = builder.trackedFaceCount;
        previewSize = builder.previewSize;
        frEngine = builder.frEngine;
        flEngine = builder.flEngine;
        /**
         * fr 线程队列大小
         */
        int frQueueSize = 5;
        if (builder.frQueueSize > 0) {
            frQueueSize = builder.frQueueSize;
            frThreadQueue = new LinkedBlockingQueue<>(frQueueSize);
        } else {
            Log.e(TAG, "frThread num must > 0,now using default value:" + frQueueSize);
        }
        frExecutor = new ThreadPoolExecutor(1, frQueueSize, 0, TimeUnit.MILLISECONDS, frThreadQueue);

        /**
         * fr 线程队列大小
         */
        int flQueueSize = 5;
        if (builder.flQueueSize > 0) {
            frQueueSize = builder.flQueueSize;
            flThreadQueue = new LinkedBlockingQueue<>(frQueueSize);
        } else {
            Log.e(TAG, "frThread num must > 0,now using default value:" + frQueueSize);
        }
        flThreadQueue = new LinkedBlockingQueue<Runnable>(flQueueSize);
        flExecutor = new ThreadPoolExecutor(1, flQueueSize, 0, TimeUnit.MILLISECONDS, flThreadQueue);
        //        if (previewSize == null) {
        //            throw new RuntimeException("previewSize must be specified!");
        //        }
    }

    /**
     * 请求获取人脸特征数据
     *
     * @param nv21
     *         图像数据
     * @param faceInfo
     *         人脸信息
     * @param width
     *         图像宽度
     * @param height
     *         图像高度
     * @param format
     *         图像格式
     * @param trackId
     *         请求人脸特征的唯一请求码，一般使用trackId
     */
    public void requestFaceFeature(byte[] nv21, FaceInfo faceInfo, int width, int height, int format, Integer trackId) {
        if (faceListener != null) {
            if (frEngine != null && frThreadQueue.remainingCapacity() > 0) {
                frExecutor.execute(new FaceRecognizeRunnable(nv21, faceInfo, width, height, format, trackId));
            } else {
                faceListener.onFaceFeatureInfoGet(null, trackId, ERROR_BUSY);
            }
        }
    }

    /**
     * 请求获取活体检测结果，需要传入活体的参数，以下参数同
     *
     * @param nv21
     *         NV21格式的图像数据
     * @param faceInfo
     *         人脸信息
     * @param width
     *         图像宽度
     * @param height
     *         图像高度
     * @param format
     *         图像格式
     * @param trackId
     *         请求人脸特征的唯一请求码，一般使用trackId
     * @param livenessType
     *         活体检测类型
     */
    public void requestFaceLiveness(byte[] nv21, FaceInfo faceInfo, int width, int height, int format, Integer trackId, LivenessType livenessType) {
        if (faceListener != null) {
            if (flEngine != null && flThreadQueue.remainingCapacity() > 0) {
                flExecutor.execute(new FaceLivenessDetectRunnable(nv21, faceInfo, width, height, format, trackId, livenessType));
            } else {
                faceListener.onFaceFeatureInfoGet(null, trackId, ERROR_BUSY);
            }
        }
    }

    /**
     * 释放对象
     */
    public void release() {
        if (!frExecutor.isShutdown()) {
            frExecutor.shutdownNow();
            frThreadQueue.clear();
        }
        if (!flExecutor.isShutdown()) {
            flExecutor.shutdownNow();
            flThreadQueue.clear();
        }
        if (faceInfoList != null) {
            faceInfoList.clear();
        }
        if (frThreadQueue != null) {
            frThreadQueue.clear();
            frThreadQueue = null;
        }
        if (flThreadQueue != null) {
            flThreadQueue.clear();
            flThreadQueue = null;
        }
        if (nameMap != null) {
            nameMap.clear();
        }
        nameMap = null;
        faceListener = null;
        faceInfoList = null;
    }

    /**
     * 处理帧数据
     *
     * @param nv21
     *         相机预览回传的NV21数据
     *
     * @return 实时人脸处理结果，封装添加了一个trackId，trackId的获取依赖于faceId，用于记录人脸序号并保存
     */
    public List<FacePreviewInfo> onPreviewFrame(byte[] nv21) {

        if (faceListener != null) {

            if (ftEngine != null) {
                faceInfoList.clear();
                long ftStartTime = System.currentTimeMillis();
                int code;
                if (previewSize == null) {
                    code = ftEngine.detectFaces(nv21, DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT, FaceEngine.CP_PAF_NV21, faceInfoList);
                } else {
                    code = ftEngine.detectFaces(nv21, previewSize.width, previewSize.height, FaceEngine.CP_PAF_NV21, faceInfoList);
                }

                if (code != ErrorInfo.MOK) {
                    faceListener.onFail(new Exception("ft failed,code is " + code));
                } else {
                    Log.i(TAG, "onPreviewFrame: ft costTime = " + (System.currentTimeMillis() - ftStartTime) + "ms");
                }
                /*
                 * 若需要多人脸搜索，删除此行代码
                 */
//                TrackUtil.keepMaxFace(faceInfoList);
                refreshTrackId(faceInfoList);
            }
            facePreviewInfoList.clear();
            for (int i = 0; i < faceInfoList.size(); i++) {
                facePreviewInfoList.add(new FacePreviewInfo(faceInfoList.get(i), currentTrackIdList.get(i)));
            }
            return facePreviewInfoList;
        } else {
            Log.e(TAG, "faceListener is null");
            facePreviewInfoList.clear();
            return facePreviewInfoList;
        }
    }

    /**
     * 刷新trackId
     *
     * @param ftFaceList
     *         传入的人脸列表
     */
    private void refreshTrackId(List<FaceInfo> ftFaceList) {
        currentTrackIdList.clear();

        for (FaceInfo faceInfo : ftFaceList) {
            currentTrackIdList.add(faceInfo.getFaceId() + trackedFaceCount);
        }
        if (ftFaceList.size() > 0) {
            currentMaxFaceId = ftFaceList.get(ftFaceList.size() - 1).getFaceId();
        }

        //刷新nameMap
        clearLeftName(currentTrackIdList);
    }

    /**
     * 获取当前的最大trackID,可用于退出时保存
     *
     * @return 当前trackId
     */
    public int getTrackedFaceCount() {
        // 引擎的人脸下标从0开始，因此需要+1
        return trackedFaceCount + currentMaxFaceId + 1;
    }

    /**
     * 新增搜索成功的人脸
     *
     * @param trackId
     *         指定的trackId
     * @param name
     *         trackId对应的人脸
     */
    public void setName(int trackId, String name) {
        if (nameMap != null) {
            nameMap.put(trackId, name);
        }
    }

    public String getName(int trackId) {
        return nameMap == null ? null : nameMap.get(trackId);
    }

    /**
     * 清除map中已经离开的人脸
     *
     * @param trackIdList
     *         最新的trackIdList
     */
    private void clearLeftName(List<Integer> trackIdList) {
        Enumeration<Integer> keys = nameMap.keys();
        while (keys.hasMoreElements()) {
            int value = keys.nextElement();
            if (!trackIdList.contains(value)) {
                nameMap.remove(value);
            }
        }
    }

    public static final class Builder {
        private FaceEngine ftEngine;
        private FaceEngine frEngine;
        private FaceEngine flEngine;
        private Camera.Size previewSize;
        private FaceListener faceListener;
        private int frQueueSize;
        private int flQueueSize;
        private int trackedFaceCount;

        public Builder() {
        }


        public Builder ftEngine(FaceEngine val) {
            ftEngine = val;
            return this;
        }

        public Builder frEngine(FaceEngine val) {
            frEngine = val;
            return this;
        }

        public Builder flEngine(FaceEngine val) {
            flEngine = val;
            return this;
        }


        public Builder previewSize(Camera.Size val) {
            previewSize = val;
            return this;
        }


        public Builder faceListener(FaceListener val) {
            faceListener = val;
            return this;
        }

        public Builder frQueueSize(int val) {
            frQueueSize = val;
            return this;
        }

        public Builder flQueueSize(int val) {
            flQueueSize = val;
            return this;
        }

        public Builder trackedFaceCount(int val) {
            trackedFaceCount = val;
            return this;
        }

        public FaceHelper build() {
            return new FaceHelper(this);
        }
    }

    /**
     * 人脸特征提取线程
     */
    public class FaceRecognizeRunnable implements Runnable {
        private FaceInfo faceInfo;
        private int width;
        private int height;
        private int format;
        private Integer trackId;
        private byte[] nv21Data;

        private FaceRecognizeRunnable(byte[] nv21Data, FaceInfo faceInfo, int width, int height, int format, Integer trackId) {
            if (nv21Data == null) {
                return;
            }
            this.nv21Data = nv21Data;
            this.faceInfo = new FaceInfo(faceInfo);
            this.width = width;
            this.height = height;
            this.format = format;
            this.trackId = trackId;
        }

        @Override
        public void run() {
            if (faceListener != null && nv21Data != null) {
                if (frEngine != null) {
                    FaceFeature faceFeature = new FaceFeature();
                    long frStartTime = System.currentTimeMillis();
                    int frCode;
                    synchronized (frEngine) {
                        frCode = frEngine.extractFaceFeature(nv21Data, width, height, format, faceInfo, faceFeature);
                    }
                    if (frCode == ErrorInfo.MOK) {
                        //                        Log.i(TAG, "run: fr costTime = " + (System.currentTimeMillis() - frStartTime) + "ms");
                        faceListener.onFaceFeatureInfoGet(faceFeature, trackId, frCode);
                    } else {
                        faceListener.onFaceFeatureInfoGet(null, trackId, frCode);
                        faceListener.onFail(new Exception("fr failed errorCode is " + frCode));
                    }
                } else {
                    faceListener.onFaceFeatureInfoGet(null, trackId, ERROR_FR_ENGINE_IS_NULL);
                    faceListener.onFail(new Exception("fr failed ,frEngine is null"));
                }
            }
            nv21Data = null;
        }
    }

    /**
     * 活体检测的线程
     */
    public class FaceLivenessDetectRunnable implements Runnable {
        private FaceInfo faceInfo;
        private int width;
        private int height;
        private int format;
        private Integer trackId;
        private byte[] nv21Data;
        private LivenessType livenessType;

        private FaceLivenessDetectRunnable(byte[] nv21Data, FaceInfo faceInfo, int width, int height, int format, Integer trackId, LivenessType livenessType) {
            if (nv21Data == null) {
                return;
            }
            this.nv21Data = nv21Data;
            this.faceInfo = new FaceInfo(faceInfo);
            this.width = width;
            this.height = height;
            this.format = format;
            this.trackId = trackId;
            this.livenessType = livenessType;
        }

        @Override
        public void run() {
            if (faceListener != null && nv21Data != null) {
                if (flEngine != null) {
                    List<LivenessInfo> livenessInfoList = new ArrayList<>();
                    int flCode;
                    synchronized (flEngine) {
                        if (livenessType == LivenessType.RGB) {
                            flCode = flEngine.process(nv21Data, width, height, format, Arrays.asList(faceInfo), FaceEngine.ASF_LIVENESS);
                        } else {
                            flCode = flEngine.processIr(nv21Data, width, height, format, Arrays.asList(faceInfo), FaceEngine.ASF_IR_LIVENESS);
                        }
                    }
                    if (flCode == ErrorInfo.MOK) {
                        if (livenessType == LivenessType.RGB) {
                            flCode = flEngine.getLiveness(livenessInfoList);
                        } else {
                            flCode = flEngine.getIrLiveness(livenessInfoList);
                        }
                    }

                    if (flCode == ErrorInfo.MOK && livenessInfoList.size() > 0) {
                        faceListener.onFaceLivenessInfoGet(livenessInfoList.get(0), trackId, flCode);
                    } else {
                        faceListener.onFaceLivenessInfoGet(null, trackId, flCode);
                        faceListener.onFail(new Exception("fl failed errorCode is " + flCode));
                    }
                } else {
                    faceListener.onFaceLivenessInfoGet(null, trackId, ERROR_FL_ENGINE_IS_NULL);
                    faceListener.onFail(new Exception("fl failed ,frEngine is null"));
                }
            }
            nv21Data = null;
        }
    }
}
