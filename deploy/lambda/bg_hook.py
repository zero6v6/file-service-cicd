"""ECS 블루/그린 수명 주기 훅 — 테스트 트래픽으로 새 버전(green)을 확인하고 넘어갈지 정함.

ECS 가 POST_TEST_TRAFFIC_SHIFT 단계에서 이 함수를 부름.
  - 테스트 리스너 규칙(헤더 X-Test: green)이 green 으로 가는 상태 → 같은 주소에 그 헤더를 붙여 부르면 green 이 답함
  - 첫 호출은 IN_PROGRESS 로 WAIT_SECONDS 만큼 기다리게 함 → 그동안 사람이 헤더를 붙여 green 을 직접 볼 수 있음
  - 다음 호출에서 /actuator/health 가 UP 이고 /api/files 가 200 이면 SUCCEEDED → 운영 트래픽을 green 으로 넘김
  - 아니면 FAILED → ECS 가 blue 로 되돌림(롤백)

환경 변수
  BASE_URL      https://www.<student_id>.cldlab.click
  WAIT_SECONDS  첫 호출 뒤 기다릴 초 (기본 60 · 0 이면 바로 검사)
  FORCE_FAIL    "true" 면 검사 단계에서 일부러 실패 (롤백 시연용)
"""
import json, os, time
import urllib.request, urllib.error

BASE_URL = os.environ["BASE_URL"].rstrip("/")
WAIT_SECONDS = int(os.environ.get("WAIT_SECONDS", "60"))
TEST_HEADER = {"X-Test": "green"}

def call(path, attempts=3, timeout=10):
    last = None
    for i in range(attempts):
        t0 = time.time()
        try:
            req = urllib.request.Request(BASE_URL + path, headers=TEST_HEADER)
            with urllib.request.urlopen(req, timeout=timeout) as res:
                body = res.read().decode()
                print(f"{path} -> {res.status} ({time.time() - t0:.2f}s)")
                return res.status, body
        except urllib.error.HTTPError as e:
            print(f"{path} -> {e.code} ({time.time() - t0:.2f}s)")
            raise
        except Exception as e:
            last = e
            print(f"{path} 시도 {i + 1}/{attempts} 실패 ({time.time() - t0:.2f}s) : {e}")
            time.sleep(3)
    raise last

def lambda_handler(event, context):
    stage = event.get("lifecycleStage")
    details = event.get("hookDetails") or {}
    print(json.dumps({"stage": stage, "hookDetails": details,
                      "target": event.get("executionDetails", {}).get("targetServiceRevisionArn")}))

    # 1) 첫 호출 — 사람이 green 을 볼 시간을 줌. hookDetails 에 표시를 남기면 다음 호출에 그대로 돌아옴
    if WAIT_SECONDS > 0 and not details.get("waited"):
        print(f"첫 호출 → {WAIT_SECONDS}초 뒤 다시 (IN_PROGRESS)")
        return {"hookStatus": "IN_PROGRESS", "callBackDelay": WAIT_SECONDS, "hookDetails": {"waited": True}}

    # 2) 롤백 시연
    if os.environ.get("FORCE_FAIL") == "true":
        print("FORCE_FAIL=true → 일부러 실패")
        return {"hookStatus": "FAILED"}

    # 3) 테스트 트래픽으로 green 확인
    try:
        status, body = call("/actuator/health")
        healthy = status == 200 and '"UP"' in body
        files_status, _ = call("/api/files")
    except Exception as e:  # 연결 실패 · 시간 초과 · 4xx/5xx
        print(f"확인 실패 : {e}")
        return {"hookStatus": "FAILED"}

    print(f"health={status} files={files_status}")
    if healthy and files_status == 200:
        return {"hookStatus": "SUCCEEDED"}
    return {"hookStatus": "FAILED"}