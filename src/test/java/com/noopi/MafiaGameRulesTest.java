package com.noopi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noopi.api.*;
import com.noopi.application.GameApplication;
import com.noopi.content.*;
import com.noopi.game.blind.*;
import com.noopi.game.liar.*;
import com.noopi.game.mafia.*;
import com.noopi.realtime.RoomEvents;
import com.noopi.room.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import static com.noopi.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;

class MafiaGameRulesTest {
    static class Events implements RoomEvents {
        record Sent(String type, Map<String,Object> payload) {}
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        public void publish(RoomRuntime room,String type,Long session,Map<String,Object> payload){sent.add(new Sent(type,Map.copyOf(payload)));}
        public void closePlayer(long room,long player){} public void closeRoom(long room){}
    }
    final ObjectMapper json = new ObjectMapper();
    Events events; GameApplication app; MafiaGameService mafia;
    long room, session; List<Long> ids; Map<Long,String> clients;

    @BeforeEach void setup() {
        events=new Events(); var random=new Random(41); var store=new RoomStore(random,Clock.systemUTC());
        LiarContent liarContent=new LiarContent(){public List<Category> categories(){return List.of(new Category("RANDOM","랜덤",true));}
            public void validateCategory(String c){} public Keyword choose(String c,Collection<Long> r){return new Keyword(1,"바다");}};
        var liar=new LiarGameService(liarContent,random,events,10);
        var blind=new BlindGameService((count,recent)->List.of(new BlindContent.Keyword(1,"가"),new BlindContent.Keyword(2,"나")),events,10);
        mafia=new MafiaGameService(random,events);
        app=new GameApplication(store,liar,new LiarStateProjection(),blind,new BlindStateProjection(),mafia,new MafiaStateProjection(mafia),events,Clock.systemUTC(),Duration.ofMinutes(2));
    }
    void players(int count){var created=app.create("c0","참가0","MALE");room=created.room().roomId();ids=new ArrayList<>();clients=new LinkedHashMap<>();
        ids.add(created.me().playerId());clients.put(created.me().playerId(),"c0");for(int i=1;i<count;i++){var p=app.join(room,"c"+i,"참가"+i,"FEMALE");ids.add(p.playerId());clients.put(p.playerId(),"c"+i);}}
    void start(int count){players(count);session=app.createSessionConfigured(room,"c0","MAFIA",Map.of()).gameSessionId();app.start(room,session,"c0");}
    Map<String,Object> state(long id){return app.state(room,clients.get(id)).gameSession().gameState();}
    String role(long id){return (String)state(id).get("myRole");}
    void error(ErrorCode code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,e->assertThat(e.code()).isEqualTo(code));}
    void roleChecks(){ids.forEach(id->app.mafiaRoleCheck(room,session,clients.get(id)));}
    @SuppressWarnings("unchecked")
    void finishNight(){for(long id:ids){var s=state(id);if(!(boolean)s.get("alive"))continue;var action=(Map<String,Object>)s.get("nightAction");if((boolean)action.get("submitted"))continue;
        String type=(String)action.get("actionType");Long target=null;if(!type.equals("CONFIRM"))target=((Number)((Map<String,Object>)((List<?>)action.get("eligibleTargets")).getFirst()).get("playerId")).longValue();
        app.mafiaNightAction(room,session,clients.get(id),type,target);}}

    @Test void compositionAndStartBoundsAreServerControlled(){
        assertThat(mafia.composition(4)).containsEntry("mafia",1).containsEntry("doctor",0).containsEntry("citizen",2);
        assertThat(mafia.composition(7)).containsEntry("mafia",2).containsEntry("doctor",1).containsEntry("citizen",3);
        assertThat(mafia.composition(12)).containsEntry("mafia",3).containsEntry("citizen",7);
        players(3);error(INVALID_GAME_CONFIG,()->app.createSessionConfigured(room,"c0","MAFIA",Map.of("mafia",2)));
        session=app.createSessionConfigured(room,"c0","MAFIA",Map.of()).gameSessionId();error(NOT_ENOUGH_PLAYERS,()->app.start(room,session,"c0"));
    }

    @Test void rolesArePrivateAndMafiaAloneSeesTeammates() throws Exception {
        start(7);assertThat(ids.stream().map(this::role).filter("MAFIA"::equals)).hasSize(2);
        for(long id:ids){String snapshot=json.writeValueAsString(state(id));assertThat(snapshot).doesNotContain("roles\":", "nightActions", "judgmentVotes");
            if(role(id).equals("MAFIA"))assertThat((List<?>)state(id).get("mafiaTeammates")).hasSize(1);else assertThat(state(id)).doesNotContainKey("mafiaTeammates");}
        app.mafiaRoleCheck(room,session,"c0");
        assertThat(json.writeValueAsString(events.sent.stream().filter(e->e.type().equals("MAFIA_ROLE_CHECKED")).toList())).doesNotContain("\"playerId\"");
        error(ROLE_ALREADY_CHECKED,()->app.mafiaRoleCheck(room,session,"c0"));
    }

    @Test void firstNightUsesRoleSpecificActionsAndRestoresPoliceHistory(){
        start(5);roleChecks();assertThat(state(ids.getFirst()).get("phase")).isEqualTo("FIRST_NIGHT");
        long police=ids.stream().filter(id->role(id).equals("POLICE")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") var action=(Map<String,Object>)state(police).get("nightAction");
        long target=((Number)((Map<?,?>)((List<?>)action.get("eligibleTargets")).getFirst()).get("playerId")).longValue();
        error(INVALID_NIGHT_ACTION,()->app.mafiaNightAction(room,session,clients.get(police),"HEAL",target));
        var response=app.mafiaNightAction(room,session,clients.get(police),"INVESTIGATE",target);
        assertThat(response).containsKey("result");error(ACTION_ALREADY_SUBMITTED,()->app.mafiaNightAction(room,session,clients.get(police),"INVESTIGATE",target));
        finishNight();assertThat(state(police).get("phase")).isEqualTo("DAY");assertThat((List<?>)state(police).get("investigationHistory")).hasSize(1);
        for(long id:ids)if(id!=police)assertThat(state(id)).doesNotContainKey("investigationHistory");
    }

    @Test void secretExecutionVoteFlowsThroughFrontendJudgmentContract() throws Exception {
        start(5);roleChecks();finishNight();app.startMafiaVote(room,session,"c0");long accused=ids.get(1);
        for(long voter:ids){long target=voter==accused?ids.get(2):accused;app.mafiaVote(room,session,clients.get(voter),1L,target);}
        assertThat(state(ids.getFirst()).get("phase")).isEqualTo("VOTE_RESULT");
        assertThat(json.writeValueAsString(events.sent.stream().filter(e->e.type().equals("MAFIA_PLAYER_VOTED")).toList())).doesNotContain("playerId","targetPlayerId");
        app.advanceMafia(room,session,"c0");var judgment=(Map<?,?>)state(ids.getFirst()).get("judgment");
        assertThat(judgment.get("requiredVoteCount")).isEqualTo(4); assertThat(judgment.get("executeCount")).isEqualTo(0); assertThat(judgment.get("saveCount")).isEqualTo(0);
        error(INVALID_VOTE_TARGET,()->app.mafiaJudgment(room,session,clients.get(accused),"EXECUTE"));
        for(long voter:ids)if(voter!=accused)app.mafiaJudgment(room,session,clients.get(voter),"EXECUTE");
        var result=state(ids.getFirst());assertThat(result.get("phase")).isEqualTo("JUDGMENT_RESULT");assertThat(result).containsEntry("executed",true);
        app.advanceMafia(room,session,"c0");
        assertThat(state(ids.getFirst()).get("phase")).isIn("EXECUTION","FINISHED");
    }
}
