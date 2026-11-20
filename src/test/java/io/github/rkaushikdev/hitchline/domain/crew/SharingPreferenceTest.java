package io.github.rkaushikdev.hitchline.domain.crew;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.domain.ForbiddenException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.time.CivilDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SharingPreferenceTest {

    private static final String ALPHA = Ids.crewId("crw_0123456789ABCDEF");
    private static final String BRAVO = Ids.crewId("crw_FEDCBA9876543210");
    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");
    private static final String CLEO = Ids.crewMemberId("mem_1111111111111111");
    private static final String TREADMILL = Ids.habitId("hab_0123456789ABCDEF");
    private static final String SLEEP = Ids.habitId("hab_FEDCBA9876543210");
    private static final CivilDate ON = d("2025-02-15");

    private static final List<Membership> MEMBERSHIPS = List.of(
            Membership.of(ALPHA, ADA, d("2025-01-01")),
            Membership.of(ALPHA, BO, d("2025-01-01"), CrewRole.LEAD),
            Membership.of(ALPHA, CLEO, d("2025-01-01")));

    private static final Viewer AS_MEMBER = new Viewer(CLEO, CrewRole.MEMBER);
    private static final Viewer AS_LEAD = new Viewer(BO, CrewRole.LEAD);

    private static CivilDate d(String text) {
        return CivilDate.parse(text);
    }

    private static AccessRequest request(Visibility fallback, Viewer viewer) {
        return new AccessRequest(viewer, SharingPreference.of(ADA, fallback), TREADMILL, MEMBERSHIPS, ON);
    }

    private static AccessRequest withMemberships(AccessRequest request, List<Membership> memberships) {
        return new AccessRequest(request.viewer(), request.preference(), request.habitId(), memberships, request.on());
    }

    @Nested
    class VisibilityValues {

        @Test
        void knowsTheThreeSettings() {
            assertThat(Visibility.isVisibility("crew")).isTrue();
            assertThat(Visibility.isVisibility("everyone")).isFalse();
        }

        @Test
        void describesEachOneInPlainWords() {
            assertThat(Visibility.PRIVATE.describe()).isEqualTo("only you");
            assertThat(Visibility.LEAD_ONLY.describe()).isEqualTo("you and your crew lead");
            assertThat(Visibility.CREW.describe()).isEqualTo("everybody on your crew");
        }
    }

    @Nested
    class Preferences {

        @Test
        void defaultsToPrivate() {
            assertThat(SharingPreference.of(ADA).fallback()).isEqualTo(Visibility.PRIVATE);
        }

        @Test
        void letsOneHabitDifferFromTheFallback() {
            SharingPreference preference =
                    SharingPreference.of(ADA, Visibility.PRIVATE).withHabitVisibility(TREADMILL, Visibility.CREW);
            assertThat(preference.visibilityFor(TREADMILL)).isEqualTo(Visibility.CREW);
            assertThat(preference.visibilityFor(SLEEP)).isEqualTo(Visibility.PRIVATE);
        }

        @Test
        void dropsBackToTheFallbackWhenTheOverrideIsCleared() {
            SharingPreference preference =
                    SharingPreference.of(ADA, Visibility.LEAD_ONLY).withHabitVisibility(TREADMILL, Visibility.CREW);
            assertThat(preference.withoutHabitVisibility(TREADMILL).visibilityFor(TREADMILL))
                    .isEqualTo(Visibility.LEAD_ONLY);
        }

        @Test
        void refusesASettingItDoesNotKnow() {
            assertThatThrownBy(() -> SharingPreference.of(ADA, "public")).hasMessageContaining("is not a visibility");
        }
    }

    @Nested
    class WhoMayRead {

        @Test
        void alwaysLetsSomebodyReadTheirOwn() {
            Viewer own = new Viewer(ADA, CrewRole.MEMBER);
            assertThat(SharingPreference.canRead(request(Visibility.PRIVATE, own)))
                    .isTrue();
        }

        @Test
        void keepsAPrivateHabitFromTheCrewAndTheLead() {
            assertThat(SharingPreference.canRead(request(Visibility.PRIVATE, AS_MEMBER)))
                    .isFalse();
            assertThat(SharingPreference.canRead(request(Visibility.PRIVATE, AS_LEAD)))
                    .isFalse();
        }

        @Test
        void opensALeadOnlyHabitToTheLeadButNotToACrewmate() {
            assertThat(SharingPreference.canRead(request(Visibility.LEAD_ONLY, AS_LEAD)))
                    .isTrue();
            assertThat(SharingPreference.canRead(request(Visibility.LEAD_ONLY, AS_MEMBER)))
                    .isFalse();
        }

        @Test
        void opensACrewHabitToEverybodyOnTheCrew() {
            assertThat(SharingPreference.canRead(request(Visibility.CREW, AS_MEMBER)))
                    .isTrue();
            assertThat(SharingPreference.canRead(request(Visibility.CREW, AS_LEAD)))
                    .isTrue();
        }

        @Test
        void shutsOutSomebodyOnAnotherCrewWhateverTheSetting() {
            Viewer outsider = new Viewer(Ids.crewMemberId("mem_2222222222222222"), CrewRole.LEAD);
            List<Membership> withOutsider = new ArrayList<>(MEMBERSHIPS);
            withOutsider.add(Membership.of(BRAVO, outsider.memberId(), d("2025-01-01"), CrewRole.LEAD));
            assertThat(SharingPreference.canRead(withMemberships(request(Visibility.CREW, outsider), withOutsider)))
                    .isFalse();
        }

        @Test
        void shutsOutAViewerWhoWasInNoCrewThatDay() {
            AccessRequest crewWide = request(Visibility.CREW, AS_MEMBER);
            AccessRequest early = new AccessRequest(
                    crewWide.viewer(), crewWide.preference(), crewWide.habitId(), MEMBERSHIPS, d("2024-06-01"));
            assertThat(SharingPreference.canRead(early)).isFalse();
        }

        @Test
        void readsTheCrewAsItWasOnTheDayAskedAbout() {
            AccessRequest later = withMemberships(
                    request(Visibility.CREW, AS_MEMBER),
                    List.of(
                            Membership.of(ALPHA, ADA, d("2025-01-01"), CrewRole.MEMBER, d("2025-01-31")),
                            Membership.of(ALPHA, CLEO, d("2025-01-01"))));
            assertThat(SharingPreference.canRead(later)).isFalse();
        }
    }

    @Nested
    class RequireRead {

        @Test
        void passesQuietlyWhenAllowed() {
            assertThatCode(() -> SharingPreference.requireRead(request(Visibility.CREW, AS_MEMBER)))
                    .doesNotThrowAnyException();
        }

        @Test
        void throwsWithBothNamesWhenNot() {
            assertThatThrownBy(() -> SharingPreference.requireRead(request(Visibility.PRIVATE, AS_MEMBER)))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> SharingPreference.requireRead(request(Visibility.PRIVATE, AS_MEMBER)))
                    .hasMessageContaining(ADA);
        }
    }

    @Nested
    class ReadableHabits {

        @Test
        void filtersAListDownToWhatTheViewerMaySee() {
            SharingPreference preference =
                    SharingPreference.of(ADA, Visibility.PRIVATE).withHabitVisibility(TREADMILL, Visibility.CREW);
            assertThat(SharingPreference.readableHabits(
                            AS_MEMBER, preference, MEMBERSHIPS, ON, List.of(TREADMILL, SLEEP)))
                    .containsExactly(TREADMILL);
        }
    }
}
