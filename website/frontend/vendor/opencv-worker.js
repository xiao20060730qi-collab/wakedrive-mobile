let ready = false;
let runtimeReadyResolver = null;

function waitForRuntime(timeoutMs = 180000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    runtimeReadyResolver = resolve;
    const check = () => {
      const runtime = self.cv;
      if (runtime && runtime.Mat && runtime.calledRun) {
        resolve(runtime);
        return;
      }
      if (Date.now() - started > timeoutMs) {
        reject(new Error('OpenCV 运行时初始化超时'));
        return;
      }
      if ((Date.now() - started) % 5000 < 120) {
        self.postMessage({ type: 'log', message: 'waiting ' + Math.round((Date.now() - started) / 1000) + 's calledRun=' + Boolean(runtime?.calledRun) + ' mat=' + Boolean(runtime?.Mat) });
      }
      setTimeout(check, 100);
    };
    check();
  });
}

self.onmessage = async event => {
  const message = event.data || {};
  if (message.type === 'init') {
    try {
      self.postMessage({ type: 'log', message: 'importing ' + message.url });
      importScripts(message.url);
      const factory = self.cv;
      self.postMessage({ type: 'log', message: 'factory=' + typeof factory });
      if (typeof factory === 'function') {
        self.cv = factory({
          locateFile: path => new URL(path, self.location.href).href,
          onRuntimeInitialized: () => {
            ready = true;
            self.postMessage({ type: 'ready' });
            if (runtimeReadyResolver) {
              const resolve = runtimeReadyResolver;
              runtimeReadyResolver = null;
              resolve(self.cv);
            }
          },
        });
      }
      await waitForRuntime();
      ready = true;
      self.postMessage({ type: 'log', message: 'runtime ready' });
    } catch (error) {
      self.postMessage({ type: 'error', message: String(error?.message || error) });
    }
    return;
  }
  if (message.type !== 'analyze') return;
  if (!ready) {
    self.postMessage({ type: 'result', id: message.id, error: 'OpenCV 尚未就绪' });
    return;
  }
  const cv = self.cv;
  let src = null;
  let gray = null;
  let mean = null;
  let stddev = null;
  let laplacian = null;
  let lapMean = null;
  let lapStd = null;
  let enhanced = null;
  let rgba = null;
  try {
    src = new cv.Mat(message.height, message.width, cv.CV_8UC4);
    src.data.set(new Uint8Array(message.data));
    gray = new cv.Mat();
    cv.cvtColor(src, gray, cv.COLOR_RGBA2GRAY);
    mean = new cv.Mat();
    stddev = new cv.Mat();
    cv.meanStdDev(gray, mean, stddev);
    laplacian = new cv.Mat();
    cv.Laplacian(gray, laplacian, cv.CV_64F);
    lapMean = new cv.Mat();
    lapStd = new cv.Mat();
    cv.meanStdDev(laplacian, lapMean, lapStd);
    enhanced = new cv.Mat();
    try {
      const clahe = cv.createCLAHE(2.0, new cv.Size(8, 8));
      clahe.apply(gray, enhanced);
    } catch (error) {
      cv.equalizeHist(gray, enhanced);
    }
    rgba = new cv.Mat();
    cv.cvtColor(enhanced, rgba, cv.COLOR_GRAY2RGBA);
    const output = new Uint8ClampedArray(rgba.data);
    self.postMessage({
      type: 'result',
      id: message.id,
      width: message.width,
      height: message.height,
      brightness: mean.data64F[0],
      contrast: stddev.data64F[0],
      sharpness: lapStd.data64F[0] ** 2,
      data: output.buffer,
    }, [output.buffer]);
  } catch (error) {
    self.postMessage({ type: 'result', id: message.id, error: String(error?.message || error) });
  } finally {
    for (const resource of [src, gray, mean, stddev, laplacian, lapMean, lapStd, enhanced, rgba]) {
      try { resource?.delete?.(); } catch (error) { /* already released */ }
    }
  }
};
