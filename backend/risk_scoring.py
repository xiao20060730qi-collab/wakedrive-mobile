"""Elapsed-time fatigue rules mirrored by frontend/js/temporal-risk.js."""
import math

DEFAULTS={"earThreshold":.25,"marThreshold":.55,"mildFrames":2,"mediumFrames":5,"heavyFrames":9,"detectIntervalMs":333}


def evaluate_risk(metrics, settings):
    if not metrics.get("face_detected"):
        return {"score":0,"risk":"未检测到人脸","severity":"none","reasons":[],"triggerType":None}
    config={**DEFAULTS,**settings}
    interval=config["detectIntervalMs"]
    mild=max(300,config["mildFrames"]*interval)
    medium=max(700,mild+100,config["mediumFrames"]*interval)
    high=max(1200,medium+100,config["heavyFrames"]*interval)
    eye=(mild,medium,high)
    mouth=(max(1500,mild),max(2500,medium),max(4000,high))
    closed=max(0,metrics.get("closed_duration_ms") or 0)
    opened=max(0,metrics.get("yawn_duration_ms") or 0)
    eye_level=mouth_level=0
    reasons=[]
    for index in range(3):
        if closed>=eye[index]:
            eye_level=index+1
        if opened>=mouth[index]:
            mouth_level=index+1
    if closed>=eye[0]:
        reasons.append(f"持续闭眼 {closed/1000:.1f} 秒")
    if opened>=mouth[0]:
        reasons.append(f"持续张嘴 {opened/1000:.1f} 秒")
    ratio=metrics.get("closed_ratio")
    if (metrics.get("window_valid_ms") or 0)>=10000 and isinstance(ratio,(int,float)) and math.isfinite(ratio):
        for index,threshold in enumerate((.15,.25,.4)):
            if ratio>=threshold:
                eye_level=max(eye_level,index+1)
        if ratio>=.15:
            reasons.append(f"近 60 秒有效检测中闭眼占比 {math.floor(ratio*100+.5)}%")
    level=max(eye_level,mouth_level)
    return {"score":(0,30,60,90)[level],"severity":("normal","mild","medium","high")[level],"risk":("正常","轻度关注","中度风险","高风险")[level],"reasons":reasons,"triggerType":None if level==0 else "YAWN" if mouth_level>eye_level else "EYE_CLOSE"}
