"""
엣지 PC 없이, 내 PC를 임시 '엣지 PC'로 삼아 FCM data-only 메시지를 관리자 폰으로 보내는 테스트 스크립트.

실제 운영 때 엣지 PC가 하게 될 발신 로직과 완전히 동일한 방식(data-only, 앱이
백그라운드/종료 상태여도 AlertFcmService.onMessageReceived가 호출됨)이라
"진짜 테스트"로 쓸 수 있다.

사전 준비
1) Firebase 콘솔 > 프로젝트 설정 > 서비스 계정 탭 > "새 비공개 키 생성"
   -> 다운로드된 json을 이 파일과 같은 폴더에 service-account.json 이름으로 저장
   (이 키는 비밀키이므로 git에 커밋하지 말 것)
2) pip install google-auth requests
3) DEVICE_TOKEN 환경변수를 채운다 (기기 고유값이므로 코드에 직접 적지 말 것).
   - DEVICE_TOKEN: 앱 실행 후 화면에 뜨는 FCM 토큰(복사 버튼으로 복사한 값)
   - PROJECT_ID는 google-services.json의 "project_info.project_id" 값으로 고정돼 있다.

실행 (Windows PowerShell 예시)
    $env:DEVICE_TOKEN = "앱에서-복사한-토큰"
    python send_test_alert.py
"""

import json
import os
import sys

import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

SERVICE_ACCOUNT_FILE = "service-account.json"
PROJECT_ID = "rtauto-sop"
DEVICE_TOKEN = os.environ.get("DEVICE_TOKEN", "")

SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]


def get_access_token() -> str:
    credentials = service_account.Credentials.from_service_account_file(
        SERVICE_ACCOUNT_FILE, scopes=SCOPES
    )
    credentials.refresh(Request())
    return credentials.token


def send_test_alert(title: str, body: str) -> None:
    access_token = get_access_token()
    url = f"https://fcm.googleapis.com/v1/projects/{PROJECT_ID}/messages:send"
    headers = {
        "Authorization": f"Bearer {access_token}",
        "Content-Type": "application/json; UTF-8",
    }
    # notification 키를 넣지 않는다 -> AlertFcmService.onMessageReceived가
    # 앱 상태(포그라운드/백그라운드/종료)와 무관하게 항상 호출된다.
    message = {
        "message": {
            "token": DEVICE_TOKEN,
            "data": {
                "title": title,
                "body": body,
            },
            "android": {"priority": "high"},
        }
    }
    resp = requests.post(url, headers=headers, data=json.dumps(message))
    print(resp.status_code, resp.text)


if __name__ == "__main__":
    if not DEVICE_TOKEN:
        sys.exit("DEVICE_TOKEN 환경변수가 비어 있습니다. 사전 준비 3번을 참고해 설정한 뒤 다시 실행하세요.")
    send_test_alert(
        title="2인 1조 위반",
        body="세정기 구역, 2인 1조 위반, 09시 10분 발생.",
    )
