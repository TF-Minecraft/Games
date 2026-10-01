package net.tfminecraft.games.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Sound;

import net.tfminecraft.games.help.HelpBook;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.WagerItemOverride;

/**
 * Runtime flags from config.yml and games.yml.
 */
public final class Cache {

    public static boolean debug = false;
    public static int stackVisibleMax = 10;
    public static float cardScale = 0.35f;
    public static int interpolationTicks = 6;
    public static double tableYOffset = 0.02;
    public static double pokerLeaveDistance = 6.0;
    public static String pokerCardSet = "french_52";
    public static String pokerLabel = "Poker";
    public static String pokerIcon = "ia.tfmc_games:oseni_1";
    public static double displayRange = 48.0;
    public static float stackLayerGap = 0.012f;
    public static double tableDiscardOffset = 0.35;
    public static int tableRecycleTicks = 8;
    public static double tableBoardOffset = 0.55;
    public static float tableCardYawOffset = 0f;
    public static float tableCardPitch = -90f;
    public static float tableCardRoll = 0f;
    public static float handDistance = 0.5f;
    public static float handSpread = 0.2f;
    public static float handLift = 1.2f;
    public static float handLayerGap = 0.01f;
    public static int handFollowTicks = 1;
    public static float handYawLimit = 60f;
    public static float handYawStick = 12f;
    public static float handSelectedDistance = 0.9f;
    public static int handSelectTicks = 10;
    public static double handSelectRange = 1.2;
    public static double handSelectRadius = 0.1;
    public static double handPosStick = 0.25;
    public static double handSitRange = 1.5;
    public static float handSitEdge = 0.45f;
    public static float handSitInset = 0.12f;
    public static float handSplitGroupGap = 0.35f;
    public static int handRevealStagger = 4;
    public static int handRevealFlip = 8;
    public static int handDealTicks = 10;
    public static float handRevealDust = 0.1f;
    public static Sound cardSound = Sound.ITEM_BOOK_PAGE_TURN;
    public static float cardSoundVolume = 1f;
    public static float cardSoundPitch = 1f;
    public static Sound chipSound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
    public static float chipSoundVolume = 1f;
    public static float chipSoundPitch = 1f;
    public static double wagerMinRange = 0.3;
    public static double wagerMaxRange = 1.2;
    public static boolean wagerIntegerDenars = true;
    public static int wagerStackMax = 6;
    public static int wagerStackUnit = 64;
    public static float wagerLayerGap = 0.02f;
    public static float wagerItemScale = 0.35f;
    public static boolean wagerRandomYaw = false;
    public static double wagerMergeRange = 0.1;
    public static double wagerYOffset = 0;
    public static int wagerVoteSeconds = 30;
    public static int wagerPlaceSeconds = 10;
    public static int wagerPayoutTicks = 10;
    public static int wagerMinPlayers = 1;
    public static boolean wagerAuditLog = false;
    public static boolean wagerShowChips = true;
    public static WagerItemOverride wagerGold;
    public static WagerItemOverride wagerSilver;
    public static final List<WagerItemOverride> wagerItems = new ArrayList<>();
    /** Catalog rank -> sort/play value, keyed by game id. Unmapped ranks stay as catalog rank. */
    public static final Map<String, Map<Integer, Integer>> gameRankValues = new HashMap<>();
    /** Maximum cards held by one player, including cards still being dealt; 0 is unlimited. */
    public static final Map<String, Integer> gameHandCardLimits = new HashMap<>();
    public static final Map<String, TableLayout> tableLayouts = new HashMap<>();
    /** Help books from help.yml, keyed by what a player types after /games help. */
    public static final Map<String, HelpBook> helpBooks = new LinkedHashMap<>();

    private Cache() {}

    public static HelpBook helpBook(String id) {
        if (id == null) {
            return null;
        }
        return helpBooks.get(id.toLowerCase(Locale.ROOT));
    }

    public static int sortValue(String gameId, int rank) {
        if (gameId == null) {
            return rank;
        }
        Map<Integer, Integer> map = gameRankValues.get(gameId.toLowerCase(Locale.ROOT));
        if (map == null) {
            return rank;
        }
        Integer mapped = map.get(rank);
        return mapped != null ? mapped : rank;
    }

    public static TableLayout layoutOf(String gameId) {
        if (gameId == null) {
            return null;
        }
        return tableLayouts.get(gameId.toLowerCase(Locale.ROOT));
    }

    public static String cardSetOf(String gameId) {
        TableLayout layout = layoutOf(gameId);
        if (layout != null && layout.cardSet() != null && !layout.cardSet().isBlank()) {
            return layout.cardSet();
        }
        return pokerCardSet;
    }

    public static String labelOf(String gameId) {
        TableLayout layout = layoutOf(gameId);
        if (layout != null && layout.label() != null && !layout.label().isBlank()) {
            return layout.label();
        }
        return pokerLabel;
    }

    public static String iconOf(String gameId) {
        TableLayout layout = layoutOf(gameId);
        if (layout != null && layout.icon() != null && !layout.icon().isBlank()) {
            return layout.icon();
        }
        return pokerIcon;
    }

    public static double leaveDistanceOf(String gameId) {
        TableLayout layout = layoutOf(gameId);
        if (layout != null && layout.leaveDistance() > 0) {
            return layout.leaveDistance();
        }
        return pokerLeaveDistance;
    }
}
