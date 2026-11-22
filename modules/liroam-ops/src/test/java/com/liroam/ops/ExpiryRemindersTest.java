package com.liroam.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liroam.common.setting.SettingScope;
import com.liroam.common.setting.Settings;
import com.liroam.ops.notify.ExpiryReminders;
import com.liroam.ops.notify.Notifications;
import com.liroam.ops.notify.NotifySettings;

/** 到期提醒（domain-design §13 notify.expiry_remind_before）：按档位各发一次、跳过已过的大档、续期后重新计、站内必发、短信按开关。 */
class ExpiryRemindersTest {

    private static OpsFixture f;
    private static ExpiryReminders reminders;
    private static Notifications inbox;

    @BeforeAll
    static void start() {
        f = OpsFixture.start("ops_expiry");
        reminders = f.bean(ExpiryReminders.class);
        inbox = f.bean(Notifications.class);
    }

    @AfterAll
    static void stop() {
        f.close();
    }

    @BeforeEach
    void reset() {
        f.reset();
    }

    private int sentFor(long card) {
        return f.h.count("lr_notification WHERE card_asset_id = ?", card);
    }

    @Test
    void eachThresholdOnceAndLargerOnesNotSentAfterSmaller() {
        long card = f.deliveredCard("O-E1", "m1");
        f.inUseUntil(card, f.now().plus(Duration.ofHours(60)));

        assertThat(reminders.runOnce()).isEqualTo(1);
        assertThat(reminders.runOnce()).isZero();
        f.h.clock.advance(Duration.ofHours(40));
        assertThat(reminders.runOnce()).isEqualTo(1);
        f.h.clock.advance(Duration.ofHours(10));
        assertThat(reminders.runOnce()).isZero();
        assertThat(sentFor(card)).isEqualTo(2);
    }

    @Test
    void closeToExpiryGetsOnlyTheSmallestThreshold() {
        long card = f.deliveredCard("O-E2", "m1");
        f.inUseUntil(card, f.now().plus(Duration.ofHours(10)));
        assertThat(reminders.runOnce()).isEqualTo(1);
        assertThat(f.h.jdbc.queryForObject("SELECT dedupe_key FROM lr_notification", String.class)).endsWith(":24");
        assertThat(reminders.runOnce()).isZero();
    }

    @Test
    void renewalStartsAFreshCycle() {
        long card = f.deliveredCard("O-E3", "m1");
        f.inUseUntil(card, f.now().plus(Duration.ofHours(20)));
        reminders.runOnce();
        f.inUseUntil(card, f.now().plus(Duration.ofHours(70)));
        assertThat(reminders.runOnce()).isEqualTo(1);
        assertThat(sentFor(card)).isEqualTo(2);
    }

    @Test
    void notEligibleCardsAreSkipped() {
        long far = f.deliveredCard("O-E4", "m1");
        f.inUseUntil(far, f.now().plus(Duration.ofHours(100)));
        long past = f.deliveredCard("O-E5", "m1");
        f.inUseUntil(past, f.now().minus(Duration.ofHours(1)));
        long cancelled = f.deliveredCard("O-E6", "m1");
        f.inUseUntil(cancelled, f.now().plus(Duration.ofHours(5)));
        f.h.jdbc.update("UPDATE lr_card_asset SET status = 'TERMINATED' WHERE id = ?", cancelled);
        assertThat(reminders.runOnce()).isZero();
    }

    @Test
    void inSiteInboxAndSmsSwitch() {
        long card = f.deliveredCard("O-E8", "m1");
        f.inUseUntil(card, f.now().plus(Duration.ofHours(30)));
        reminders.runOnce();
        assertThat(f.sms.sent).isEmpty();
        assertThat(f.h.jdbc.queryForObject("SELECT sms_status FROM lr_notification", String.class)).isEqualTo("NONE");

        Notifications.Inbox box = inbox.mine("m1");
        assertThat(box.unread()).isEqualTo(1);
        assertThat(box.items()).singleElement().satisfies(n -> {
            assertThat(n.title()).isEqualTo("旅行卡即将到期");
            assertThat(n.body()).contains("尾号").contains("我的卡");
            assertThat(n.cardAssetId()).isEqualTo(card);
        });
        inbox.markAllRead("m1");
        assertThat(inbox.mine("m1").unread()).isZero();
        assertThat(inbox.mine("m2").items()).isEmpty();

        Settings settings = f.bean(Settings.class);
        settings.set(NotifySettings.EXPIRY_SMS_ENABLED, SettingScope.GLOBAL, true, OpsFixture.OP);
        f.h.clock.advance(Duration.ofHours(10));
        reminders.runOnce();
        assertThat(f.sms.sent).singleElement().asString().startsWith("m1:").contains("尾号");
        assertThat(f.h.jdbc.queryForObject("SELECT sms_status FROM lr_notification ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("SENT");

        long other = f.deliveredCard("O-E9", "m2");
        f.inUseUntil(other, f.now().plus(Duration.ofHours(5)));
        f.sms.succeed = false;
        reminders.runOnce();
        assertThat(f.h.jdbc.queryForObject("SELECT sms_status FROM lr_notification WHERE card_asset_id = ?", String.class, other)).isEqualTo("FAILED");
    }

    @Test
    void thresholdsAreConfigurable() {
        f.bean(Settings.class).set(NotifySettings.EXPIRY_REMIND_BEFORE, SettingScope.GLOBAL, "48h", OpsFixture.OP);
        long card = f.deliveredCard("O-E10", "m1");
        f.inUseUntil(card, f.now().plus(Duration.ofHours(60)));
        assertThat(reminders.runOnce()).isZero();
        f.inUseUntil(card, f.now().plus(Duration.ofHours(40)));
        assertThat(reminders.runOnce()).isEqualTo(1);
        f.h.clock.advance(Duration.ofHours(30));
        assertThat(reminders.runOnce()).isZero();
    }
}
