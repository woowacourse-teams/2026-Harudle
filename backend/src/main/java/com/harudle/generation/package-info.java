/**
 * 일기를 네 컷 이미지로 만드는 생성 작업을 관리한다.
 *
 * <p>일일 사용량, 멱등성, 생성 상태, 실패와 고아 작업 복구를 소유한다. LLM, 이미지 생성 API와 이미지 저장소는
 * application port를 구현하는 infrastructure 어댑터로 연결한다.</p>
 *
 * <p><strong>임시 파일:</strong> 이 패키지에 실제 구현 클래스가 추가되면 삭제한다.</p>
 */
package com.harudle.generation;
