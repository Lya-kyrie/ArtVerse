package com.artverse.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
@Setter
public class MangaGenerationJob {

    private final Long chapterId;
    private final List<String> scenes;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final List<String[]> eventHistory = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private volatile boolean completed = false;

    public MangaGenerationJob(Long chapterId, List<String> scenes) {
        this.chapterId = chapterId;
        this.scenes = scenes;
    }

    public void addSubscriber(SseEmitter emitter) {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));

        for (String[] event : eventHistory) {
            try {
                emitter.send(SseEmitter.event().name(event[0]).data(event[1], MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                subscribers.remove(emitter);
                return;
            }
        }
    }

    public void broadcastEvent(String eventName, String data) {
        eventHistory.add(new String[]{eventName, data});
        if (eventHistory.size() > 300) {
            eventHistory.removeFirst();
        }
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON);
        for (SseEmitter subscriber : subscribers) {
            try {
                subscriber.send(builder);
            } catch (Exception e) {
                subscribers.remove(subscriber);
            }
        }
    }

    public void complete() {
        completed = true;
        running = false;
        for (SseEmitter subscriber : subscribers) {
            try {
                subscriber.complete();
            } catch (Exception ignored) {
            } finally {
                subscribers.remove(subscriber);
            }
        }
        subscribers.clear();
    }

    public void error(String message) {
        running = false;
        for (SseEmitter subscriber : subscribers) {
            try {
                subscriber.completeWithError(new RuntimeException(message));
            } catch (Exception ignored) {
            } finally {
                subscribers.remove(subscriber);
            }
        }
        subscribers.clear();
    }
}
