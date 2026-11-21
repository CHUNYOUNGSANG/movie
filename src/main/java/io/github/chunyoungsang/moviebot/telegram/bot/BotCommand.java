package io.github.chunyoungsang.moviebot.telegram.bot;

/**
 * 봇 명령어 하나 (OCP).
 * 새 명령어는 이 인터페이스를 구현한 빈을 하나 추가하면 된다.
 * 명령 분배(BotCommandDispatcher)와 "/" 자동완성 메뉴(BotCommandMenu)는 수정하지 않는다.
 * 메뉴 노출 순서는 @Order 로 정한다.
 */
public interface BotCommand {

    /** 명령어 이름 ("/" 제외). 텔레그램 규칙상 소문자 영문·숫자·밑줄만 가능 */
    String name();

    /** "/" 자동완성 메뉴에 보일 설명 */
    String description();

    void handle(BotMessage message);
}
