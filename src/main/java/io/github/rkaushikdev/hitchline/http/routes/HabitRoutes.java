package io.github.rkaushikdev.hitchline.http.routes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.hitchline.app.HabitsService;
import io.github.rkaushikdev.hitchline.app.HabitsService.CreateHabitCommand;
import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.domain.habit.Habit;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTarget;
import io.github.rkaushikdev.hitchline.domain.habit.HabitTemplate;
import io.github.rkaushikdev.hitchline.http.Auth;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.http.HttpApp;
import io.github.rkaushikdev.hitchline.http.Pagination;
import io.github.rkaushikdev.hitchline.http.Pagination.PageBody;
import io.github.rkaushikdev.hitchline.http.Query;
import io.github.rkaushikdev.hitchline.http.Schemas;
import io.github.rkaushikdev.hitchline.http.Schemas.ApplyTemplateBody;
import io.github.rkaushikdev.hitchline.http.Schemas.CreateHabitBody;
import io.github.rkaushikdev.hitchline.http.Schemas.UpdateHabitBody;
import io.github.rkaushikdev.hitchline.http.Views;
import io.github.rkaushikdev.hitchline.http.Views.HabitView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Habit routes. A member may do anything to their own habits; anybody else needs the admin scope,
 * which is what a coordinator setting up a new crew has.
 */
@RestController
public final class HabitRoutes {

    private final HabitsService habits;

    public HabitRoutes(Ports ports) {
        this.habits = new HabitsService(ports);
    }

    /** Registers the habit routes on {@code app}. */
    public static void register(HttpApp app, Ports ports) {
        app.addRoutes(new HabitRoutes(ports));
    }

    /**
     * One template of the standard set.
     *
     * @param key what it is asked for by
     * @param name the habit's name
     * @param cadence how often, as the domain writes it
     * @param target the target, as the domain writes it
     * @param tags short labels
     * @param note what it is for
     */
    public record TemplateView(
            String key, String name, String cadence, Optional<String> target, List<String> tags, String note) {}

    /**
     * The standard set.
     *
     * @param templates every template, in library order
     */
    public record TemplatesBody(List<TemplateView> templates) {}

    @PostMapping("/habits")
    public ResponseEntity<HabitView> create(
            @RequestBody(required = false) JsonNode payload, HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        CreateHabitBody body = Schemas.parseBody(Schemas.CREATE_HABIT, payload);
        String memberId = Ids.crewMemberId(body.memberId());
        Auth.requireSelfOrAdmin(request, memberId);
        Habit created = habits.create(new CreateHabitCommand(
                memberId,
                body.name(),
                body.cadence(),
                body.target(),
                body.startsOn(),
                body.endsOn(),
                body.tags().orElse(List.of()),
                body.graceDays()));
        return ResponseEntity.status(201).body(Views.habitView(created));
    }

    @PostMapping("/habits/from-template")
    public ResponseEntity<HabitView> fromTemplate(
            @RequestBody(required = false) JsonNode payload, HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        ApplyTemplateBody body = Schemas.parseBody(Schemas.APPLY_TEMPLATE, payload);
        String memberId = Ids.crewMemberId(body.memberId());
        Auth.requireSelfOrAdmin(request, memberId);
        Habit created = habits.applyTemplate(memberId, body.key(), body.startsOn(), body.endsOn());
        return ResponseEntity.status(201).body(Views.habitView(created));
    }

    @GetMapping("/templates")
    public TemplatesBody templates(HttpServletRequest request) {
        Auth.requireScope(request, Scope.READ);
        return new TemplatesBody(HabitTemplate.library().stream()
                .map(template -> new TemplateView(
                        template.key(),
                        template.name(),
                        template.cadence().format(),
                        template.target().map(HabitTarget::format),
                        template.tags(),
                        template.note()))
                .toList());
    }

    @GetMapping("/habits")
    public PageBody<HabitView> list(HttpServletRequest request) {
        String raw = Query.stringOf(request, "memberId")
                .orElseThrow(() -> ValidationException.field("memberId", "is needed to list habits"));
        String memberId = Ids.crewMemberId(raw);
        Auth.requireSelfOrAdmin(request, memberId);
        List<Habit> found = habits.listFor(memberId);
        return Pagination.pageBody(
                Pagination.paginate(found, Pagination.readPageRequest(request), Habit::id), Views::habitView);
    }

    @GetMapping("/habits/{habitId}")
    public HabitView read(@PathVariable("habitId") String habitId, HttpServletRequest request) {
        Habit entry = habits.get(Ids.habitId(habitId));
        Auth.requireSelfOrAdmin(request, entry.memberId());
        return Views.habitView(entry);
    }

    @PatchMapping("/habits/{habitId}")
    public HabitView change(
            @PathVariable("habitId") String habitId,
            @RequestBody(required = false) JsonNode payload,
            HttpServletRequest request) {
        Auth.requireScope(request, Scope.WRITE);
        UpdateHabitBody body = Schemas.parseBody(Schemas.UPDATE_HABIT, payload);
        String id = Ids.habitId(habitId);
        Habit entry = habits.get(id);
        Auth.requireSelfOrAdmin(request, entry.memberId());

        if (body.name().isPresent()) {
            entry = habits.rename(id, body.name().get());
        }
        if (body.cadence().isPresent()) {
            entry = habits.changeCadence(id, body.cadence().get());
        }
        if (body.target().isPresent()) {
            entry = habits.changeTarget(id, body.target().get().target());
        }
        if (body.tags().isPresent()) {
            entry = habits.changeTags(id, body.tags().get());
        }
        if (body.status().isPresent()) {
            entry = habits.moveTo(id, body.status().get());
        }
        return Views.habitView(entry);
    }
}
