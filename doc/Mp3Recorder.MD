# Mp3Recorder

### 使用方法

```
filePath = FileUtils.getAppPath() + UUID.randomUUID().toString() + ".mp3";
// kotlin:  mRecorder = recorderBuilder(permissionListener = this, recordListener = this)
//设置
//MediaRecorder.AudioSource.VOICE_COMMUNICATION;//**对麦克风中类似ip通话的交流声音进行识别，默认会开启回声消除和自动增益*/
mRecorder = new MP3Recorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION,BuildConfig.DEBUG)      
			.setOutputFile(filePath)        
			.setRecordListener(simRecordListener)    			
			.setPermissionListener(simRecordListener)        
			.setMaxTime(1800*1000);
//开始录制
mRecorder.start();
//暂停录制
mRecorder.onPause();
//重新开始录制
mRecorder.onResume();
//完成录制
mRecorder.stop();
```

#### 也支持背景音乐，但不能戴耳机

```
 mRecorder?.bgPlayer  //获取背景音乐播放器
```

