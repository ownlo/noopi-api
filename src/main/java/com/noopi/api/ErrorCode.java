package com.noopi.api;

public enum ErrorCode {
    ROOM_NOT_FOUND(404, "방을 찾을 수 없습니다."),
    ROOM_CLOSED(409, "종료된 방입니다."),
    PLAYER_NOT_IN_ROOM(403, "방에 참가한 Player가 아닙니다."),
    NICKNAME_ALREADY_EXISTS(409, "이미 사용 중인 닉네임입니다."),
    INVALID_NICKNAME(400, "닉네임은 앞뒤 공백 제거 후 1~5글자여야 합니다."),
    INVALID_GENDER(400, "성별은 MALE 또는 FEMALE이어야 합니다."),
    NOT_ROOM_HOST(403, "방장만 수행할 수 있습니다."),
    GAME_SESSION_NOT_FOUND(404, "게임을 찾을 수 없습니다."),
    ACTIVE_GAME_SESSION_EXISTS(409, "이미 준비 또는 진행 중인 게임이 있습니다."),
    GAME_SESSION_NOT_READY(409, "시작할 수 있는 게임이 아닙니다."),
    GAME_SESSION_ALREADY_FINISHED(409, "이미 종료된 게임입니다."),
    UNSUPPORTED_GAME_TYPE(400, "지원하지 않는 게임입니다."),
    INVALID_GAME_CONFIG(400, "게임 설정이 올바르지 않습니다."),
    PLAYER_NOT_IN_GAME(403, "현재 게임 참가자가 아닙니다."),
    NOT_ENOUGH_PLAYERS(422, "최소 3명이 필요합니다."),
    TOO_MANY_PLAYERS(422, "최대 12명까지 참가할 수 있습니다."),
    INVALID_CATEGORY(400, "유효하지 않은 카테고리입니다."),
    NO_AVAILABLE_KEYWORD(422, "사용 가능한 제시어가 없습니다."),
    INVALID_GAME_PHASE(409, "현재 게임 단계에서 수행할 수 없습니다."),
    ROLE_ALREADY_CHECKED(409, "이미 역할을 확인했습니다."),
    VOTE_ALREADY_STARTED(409, "이미 투표가 시작되었습니다."),
    INVALID_VOTE_ROUND(409, "현재 투표 라운드와 일치하지 않습니다."),
    ALREADY_VOTED(409, "이미 이번 투표에 참여했습니다."),
    CANNOT_VOTE_SELF(422, "자기 자신에게 투표할 수 없습니다."),
    INVALID_VOTE_TARGET(422, "유효한 투표 후보가 아닙니다."),
    NOT_LIAR(403, "라이어만 수행할 수 있습니다."),
    GUESS_ALREADY_SUBMITTED(409, "이미 추측을 제출했습니다."),
    INVALID_ANSWER(400, "정답을 입력해주세요."),
    PLAYER_NOT_DISCONNECTED(409, "연결 중인 Player는 제외할 수 없습니다."),
    PLAYER_NOT_EXCLUDABLE(422, "아직 제외할 수 없는 Player입니다.");

    public final int status;
    public final String message;
    ErrorCode(int status, String message) { this.status = status; this.message = message; }
    public DomainException exception() { return new DomainException(this); }
    public void require(boolean condition) { if (!condition) throw exception(); }
}
