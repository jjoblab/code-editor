package jo.codeeditor.demo;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Écran d'accueil de la démo : vitrine des capacités de la bibliothèque.
 *
 * <p>Chaque carte ouvre l'atelier préconfiguré pour la fonctionnalité
 * concernée (échantillon, moteur LSP, scheme) — l'accueil ne fait QUE
 * ça : choisir un point d'entrée. Toute la démonstration réelle vit
 * dans {@link EditorActivity}, avec un tiroir complet pour explorer
 * les 29 langages au-delà des cartes.</p>
 */
public class HomeActivity extends AppCompatActivity {

    /** Une carte de capacité de l'accueil. */
    private static final class Card {
        final String badge;
        final int badgeColor;
        final int titleRes;
        final int descriptionRes;
        final String sampleLabel;
        final String sampleId;
        final boolean lsp;
        final boolean scheme;

        Card(String badge, int badgeColor, int titleRes, int descriptionRes,
                String sampleLabel, String sampleId, boolean lsp, boolean scheme) {
            this.badge = badge;
            this.badgeColor = badgeColor;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
            this.sampleLabel = sampleLabel;
            this.sampleId = sampleId;
            this.lsp = lsp;
            this.scheme = scheme;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF09090B);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(header());
        root.addView(statsRow());
        root.addView(cards());

        Button settings = new Button(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        settings.setText(R.string.home_settings);
        settings.setTextColor(0xFFA1A1AA);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsParams.topMargin = dp(16);
        root.addView(settings, settingsParams);
        settings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            overridePendingTransition(R.anim.screen_fade_in, R.anim.screen_fade_out);
        });

        setContentView(scroll);
    }

    private LinearLayout header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.home_title);
        title.setTextSize(26f);
        title.setTextColor(0xFFE4E4E7);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.home_subtitle);
        subtitle.setTextSize(13.5f);
        subtitle.setTextColor(0xFF71717A);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(4);
        subParams.bottomMargin = dp(18);

        header.addView(title);
        header.addView(subtitle, subParams);
        return header;
    }

    private LinearLayout statsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] stats = {getString(R.string.home_stat_languages),
                getString(R.string.home_stat_grammars),
                getString(R.string.home_stat_modules)};
        for (String stat : stats) {
            TextView chip = new TextView(this);
            chip.setText(stat);
            chip.setTextSize(11.5f);
            chip.setTextColor(0xFF38BDF8);
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            chip.setBackground(chipBackground());
            chip.setSingleLine(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            row.addView(chip, params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(18);
        row.setLayoutParams(rowParams);
        return row;
    }

    private LinearLayout cards() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        List<Card> cards = new ArrayList<>();
        cards.add(new Card("LSP", 0xFF38BDF8, R.string.home_card_lsp_title,
                R.string.home_card_lsp_description, "Java", "java", true, false));
        cards.add(new Card("{ }", 0xFF34D399, R.string.home_card_editor_title,
                R.string.home_card_editor_description, "Java", "java", false, false));
        cards.add(new Card("TM", 0xFFFBBF24, R.string.home_card_textmate_title,
                R.string.home_card_textmate_description, "C#", "csharp", false, false));
        cards.add(new Card("< >", 0xFF38BDF8, R.string.home_card_preview_title,
                R.string.home_card_preview_description, "XML", "xml", false, false));
        cards.add(new Card("1L", 0xFFF87171, R.string.home_card_stress_title,
                R.string.home_card_stress_description, "Minifié (JS)", "minified-js", false, false));
        cards.add(new Card("◐", 0xFFA78BFA, R.string.home_card_themes_title,
                R.string.home_card_themes_description, "Java", "java", false, true));
        // 4.5.1 : bancs des chantiers récents — postfix (table du
        // profil, réécriture de l'expression à la validation).
        cards.add(new Card("fx", 0xFF34D399, R.string.home_card_postfix_title,
                R.string.home_card_postfix_description, "Postfix (Java)", "postfix-java",
                false, false));

        for (Card card : cards) {
            grid.addView(cardView(card));
        }
        return grid;
    }

    private View cardView(final Card card) {
        com.google.android.material.card.MaterialCardView cardView =
                new com.google.android.material.card.MaterialCardView(this);
        cardView.setCardBackgroundColor(0xFF18181B);
        cardView.setRadius(dp(14));
        cardView.setStrokeColor(0xFF27272A);
        cardView.setStrokeWidth(1);
        cardView.setClickable(true);
        cardView.setFocusable(true);
        cardView.setContentDescription(getString(card.titleRes));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView badge = new TextView(this);
        badge.setText(card.badge);
        badge.setTextSize(12f);
        badge.setTextColor(0xFF09090B);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(badgeBackground(card.badgeColor));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                dp(46), dp(46));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dp(14);

        TextView title = new TextView(this);
        title.setText(card.titleRes);
        title.setTextSize(15f);
        title.setTextColor(0xFFE4E4E7);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        TextView description = new TextView(this);
        description.setText(card.descriptionRes);
        description.setTextSize(12f);
        description.setTextColor(0xFFA1A1AA);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(3);

        texts.addView(title);
        texts.addView(description, descParams);

        row.addView(badge, badgeParams);
        row.addView(texts, textParams);
        cardView.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cardView.setOnClickListener(v -> {
            Intent intent = new Intent(this, EditorActivity.class);
            intent.putExtra(EditorActivity.EXTRA_SAMPLE_ID, card.sampleId);
            intent.putExtra(EditorActivity.EXTRA_SAMPLE_LABEL, card.sampleLabel);
            intent.putExtra(EditorActivity.EXTRA_LSP, card.lsp);
            intent.putExtra(EditorActivity.EXTRA_SCHEME, card.scheme);
            // Transition « fade through » : l'accueil s'efface en
            // reculant, l'atelier monte doucement — le miroir exact
            // est rejoué au retour (finish() surchargé de l'atelier).
            startActivity(intent, androidx.core.app.ActivityOptionsCompat
                    .makeCustomAnimation(this, R.anim.atelier_enter, R.anim.home_exit)
                    .toBundle());
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        cardView.setLayoutParams(params);
        return cardView;
    }

    private android.graphics.drawable.GradientDrawable chipBackground() {
        android.graphics.drawable.GradientDrawable chip =
                new android.graphics.drawable.GradientDrawable();
        chip.setCornerRadius(dp(12));
        chip.setColor(0xFF0C1520);
        chip.setStroke(1, 0xFF155E75);
        return chip;
    }

    private android.graphics.drawable.GradientDrawable badgeBackground(int color) {
        android.graphics.drawable.GradientDrawable badge =
                new android.graphics.drawable.GradientDrawable();
        badge.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        badge.setColor(color);
        return badge;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
