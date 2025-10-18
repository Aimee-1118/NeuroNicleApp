# NeuroNicle E2 - 안드로이드 뇌파 시각화 앱

## 프로젝트 개요

이 프로젝트는 락싸(LAXTHA)의 2채널 뇌파 측정 기기인 **neuroNicle E2**와 연동되는 안드로이드 애플리케이션입니다.

기기에서 실시간으로 전송되는 뇌파 데이터를 블루투스 SPP(Serial Port Profile) 통신으로 수신하여, LXSDF T2 프로토콜을 기반으로 데이터를 파싱하고, 이를 사용자가 직관적으로 볼 수 있도록 실시간 그래프로 시각화합니다.

## 주요 기능

* **블루투스 기기 연동**: 페어링된 neuroNicle E2 장치를 검색하고 연결합니다.
* **실시간 데이터 파싱**: 기기에서 전송되는 36바이트 LXSDF T2 패킷을 실시간으로 파싱하여 2채널의 뇌파 값(µV)을 추출합니다.
* **실시간 그래프 시각화**: MPAndroidChart 라이브러리를 사용하여 두 채널의 뇌파 데이터를 실시간 라인 그래프로 표시합니다.
* **뇌파 상태 분석**: 뇌파 신호의 진폭(Amplitude)을 기준으로 'Relaxed(안정)' 또는 'Active(활동)' 상태를 간단하게 표시합니다.
* **CSV 데이터 저장**: '데이터 저장' 버튼을 누르면 측정된 뇌파 데이터를 `Timestamp, Channel1, Channel2` 형식의 CSV 파일로 스마트폰의 `Documents/NeuroNicleApp` 폴더에 저장합니다.
* **그래프 이미지 저장**: 데이터 저장을 중지할 때, 현재까지의 뇌파 그래프를 PNG 이미지 파일로 캡처하여 스마트폰 갤러리(`Pictures/NeuroNicleApp` 폴더)에 함께 저장합니다.
* **측정 제어**: '측정 중지' 버튼으로 실시간 데이터 수신 및 그래프 업데이트를 언제든 중지할 수 있습니다.

## 사용 기술

* **언어**: Kotlin
* **환경**: Android Studio
* **핵심 라이브러리**:
    * `androidx.lifecycle:lifecycle-scope`: 코루틴을 사용한 비동기 블루투스 통신 및 데이터 처리
    * `com.github.PhilJay:MPAndroidChart`: 실시간 뇌파 그래프 시각화
