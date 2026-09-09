package com.mycards.ui.search;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.mycards.R;
import com.mycards.cards.CardFace;
import com.mycards.cards.CardStatus;
import com.mycards.ui.CardFaces;
import com.mycards.ui.Formats;

import java.util.List;

public class CardRowAdapter extends ListAdapter<CardRow, RecyclerView.ViewHolder> {

    public interface OnCardClick {
        void onCard(CardRow row);
    }

    /** Tapping the archive header opens or shuts the group. */
    public interface OnGroupToggle {
        void onToggle();
    }

    private static final int TYPE_CARD = 0;
    private static final int TYPE_HEADER = 1;

    private final OnCardClick listener;
    private final OnGroupToggle groupToggle;

    /** The accent the matched words are drawn in; resolved from the theme by the activity. */
    private final int highlightColor;

    public CardRowAdapter(int highlightColor, OnCardClick listener, OnGroupToggle groupToggle) {
        super(DIFF);
        this.highlightColor = highlightColor;
        this.listener = listener;
        this.groupToggle = groupToggle;
    }

    // DiffUtilEquals warns about "==" inside areContentsTheSame, because comparing two
    // objects by identity there is a classic way to produce a list that never redraws. It
    // cannot tell that CardStatus is an enum, where identity is equality and "==" is both
    // correct and the idiom. Suppressed rather than written as .equals(), which would only
    // be pretending to fix something.
    @android.annotation.SuppressLint("DiffUtilEquals")
    private static final DiffUtil.ItemCallback<CardRow> DIFF = new DiffUtil.ItemCallback<CardRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull CardRow a, @NonNull CardRow b) {
            return a.cardId == b.cardId;
        }

        @Override
        public boolean areContentsTheSame(@NonNull CardRow a, @NonNull CardRow b) {
            if (a.isHeader() || b.isHeader()) {
                // The chevron has to turn when the group opens, and the count in the label
                // changes as a query narrows what is in it.
                return a.isHeader() == b.isHeader()
                        && a.headerExpanded == b.headerExpanded
                        && a.headerLabel.equals(b.headerLabel);
            }
            return a.score == b.score
                    && a.remaining == b.remaining
                    // Governs the depletion bar, which redraws only when told to.
                    && a.initialAmount == b.initialAmount
                    && a.status == b.status
                    // Drives the badge in the corner, and crosses its threshold overnight
                    // without anything else on the row changing.
                    && a.isExpiringSoon() == b.isExpiringSoon()
                    && a.matchedByCardName == b.matchedByCardName
                    && a.hasUnreconciledMismatch == b.hasUnreconciledMismatch
                    // Compares the highlight as well as the text, so a row already showing
                    // the right shop still redraws when the query moves within its name.
                    && a.matchedStores.equals(b.matchedStores);
        }
    };

    @Override
    public int getItemViewType(int position) {
        return getItem(position).isHeader() ? TYPE_HEADER : TYPE_CARD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new GroupVH(inflater.inflate(R.layout.item_wallet_group, parent, false));
        }
        return new VH(inflater.inflate(R.layout.item_card_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        CardRow row = getItem(position);
        if (holder instanceof GroupVH) {
            ((GroupVH) holder).bind(row, groupToggle);
        } else {
            ((VH) holder).bind(row, listener, highlightColor);
        }
    }

    /** The archive group's header: a label, a chevron, and the whole row as the control. */
    static class GroupVH extends RecyclerView.ViewHolder {

        private final TextView label;
        private final ImageView chevron;

        GroupVH(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.groupLabel);
            chevron = itemView.findViewById(R.id.groupChevron);
        }

        void bind(CardRow row, OnGroupToggle toggle) {
            label.setText(row.headerLabel);
            // One drawable serving both states, turned over rather than swapped, so the two
            // can never drift apart.
            chevron.setRotation(row.headerExpanded ? 180f : 0f);

            // Said out loud for a screen reader, which cannot see which way the chevron
            // points. setStateDescription is the API meant for exactly this: it reads after
            // the label without becoming part of it.
            ViewCompat.setStateDescription(itemView, itemView.getContext().getString(
                    row.headerExpanded ? R.string.group_expanded : R.string.group_collapsed));

            itemView.setOnClickListener(v -> toggle.onToggle());
        }
    }

    static class VH extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView subtitle;
        private final TextView amount;
        private final TextView initialAmount;
        private final TextView expiry;
        private final TextView matchReason;
        private final TextView onlineBadge;
        private final TextView warning;
        private final TextView statusBadge;
        private final ProgressBar depletion;

        /** Everything inside the card, which is what carries the card's face. */
        private final View content;

        /** The lift a live card has in the layout, kept so a retired one can be given it back. */
        private final float liveElevation;

        /** The two on-face text colours, read once rather than on every bind. */
        private final int onFace;
        private final int onFaceVariant;

        /** The pill for this row’s face, chosen in bindRetired and applied in bind. */
        private int warningPill = R.drawable.bg_face_badge;
        private final int pillPadH;
        private final int pillPadV;

        VH(@NonNull View itemView) {
            super(itemView);
            content = itemView.findViewById(R.id.cardContent);
            onFace = itemView.getContext().getColor(R.color.on_face);
            onFaceVariant = itemView.getContext().getColor(R.color.on_face_variant);
            float density = itemView.getResources().getDisplayMetrics().density;
            pillPadH = Math.round(10f * density);
            pillPadV = Math.round(5f * density);
            liveElevation = itemView instanceof MaterialCardView
                    ? ((MaterialCardView) itemView).getCardElevation()
                    : 0f;
            title = itemView.findViewById(R.id.cardTitle);
            subtitle = itemView.findViewById(R.id.cardSubtitle);
            amount = itemView.findViewById(R.id.amount);
            initialAmount = itemView.findViewById(R.id.initialAmount);
            expiry = itemView.findViewById(R.id.expiry);
            matchReason = itemView.findViewById(R.id.matchReason);
            onlineBadge = itemView.findViewById(R.id.onlineBadge);
            warning = itemView.findViewById(R.id.warning);
            statusBadge = itemView.findViewById(R.id.statusBadge);
            depletion = itemView.findViewById(R.id.depletion);
        }

        void bind(CardRow row, OnCardClick listener, int highlightColor) {
            android.content.Context ctx = itemView.getContext();

            title.setText(row.title);
            if (row.subtitle != null) {
                subtitle.setText(row.subtitle);
                subtitle.setVisibility(View.VISIBLE);
            } else {
                subtitle.setVisibility(View.GONE);
            }

            amount.setText(Formats.money(row.remaining, row.currency));
            bindStatus(ctx, row);
            bindRetired(row);
            bindDepletion(ctx, row);

            // Nothing to say when the card has no expiry, so say nothing rather than
            // spending a line telling the user something is absent.
            if (row.expiryDate == null || row.expiryDate.trim().isEmpty()) {
                expiry.setVisibility(View.GONE);
            } else {
                expiry.setVisibility(View.VISIBLE);
                bindExpiry(ctx, row);
            }

            // White lifts off all eight faces and is invisible on a retired card, which is
            // an ordinary surface. The accent is the reverse. So the highlight follows the
            // row rather than the screen.
            CharSequence reason = describeMatch(ctx, row, row.status.isRetired()
                    ? themeColor(androidx.appcompat.R.attr.colorPrimary)
                    : highlightColor);
            matchReason.setText(reason);
            // A card with no merchant list and no query to answer has nothing to say here,
            // and an empty TextView is not nothing: it keeps its line height and its top
            // margin, leaving a blank band inside the card. Most visible on the archive rows,
            // where a lapsed card would show a gap between its expiry and the note below it.
            matchReason.setVisibility(reason.length() == 0 ? View.GONE : View.VISIBLE);

            // The badge describes the merchants listed above it, so it only makes sense when
            // merchants are actually what is being shown — the same condition describeMatch
            // uses, or the two disagree and a named shop loses its badge.
            onlineBadge.setVisibility(
                    row.hasOnlineMatch && !row.matchedByCardProperName ? View.VISIBLE : View.GONE);

            // Two very different notes shared one treatment, and the quieter one came off
            // worse for it: a missing shop list is a fact about our data, while an unlogged
            // transaction is money the card has lost that the app cannot account for. Drawn
            // identically, the first borrowed the second’s urgency and, as a filled pill,
            // looked like a button that would do something about it.
            if (row.hasUnreconciledMismatch) {
                warning.setText(ctx.getString(R.string.unlogged_transaction_title));
                warning.setBackgroundResource(warningPill);
                warning.setPadding(pillPadH, pillPadV, pillPadH, pillPadV);
                warning.setVisibility(View.VISIBLE);
            } else if (!row.hasStoreList()) {
                warning.setText(ctx.getString(R.string.store_list_unavailable));
                warning.setBackground(null);
                warning.setPadding(0, 0, 0, 0);
                warning.setVisibility(View.VISIBLE);
            } else {
                warning.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> listener.onCard(row));
        }

        /**
         * Fills the row's one badge slot with whichever single fact most needs saying.
         *
         * <p>Why the card is in the archive, or — for a card still in use — that it is about
         * to lapse. The two are mutually exclusive, so they share the corner rather than
         * each claiming one and leaving a gap on every row that has neither.
         *
         * <p>Every retired state is marked, expiry included, even though the line below also
         * reports it. Seen next to its neighbours the alternative was worse: three cards in
         * the archive, two wearing a badge and one bare, and the odd one out reads as an
         * oversight rather than a decision. A set of states is only legible as a set when
         * every member is marked the same way — so the word is worth repeating, and the line
         * below still carries the part the badge does not, which is when.
         *
         * <p>The nudge for a card about to lapse moved here from coloured text. On a wallet
         * of eight hues there is no single colour that reads as "warning" on all of them —
         * amber is invisible on the amber face — so urgency is carried by a word in a pill
         * that looks the same everywhere.
         */
        private void bindStatus(android.content.Context ctx, CardRow row) {
            Integer label = null;
            if (row.status == CardStatus.EMPTY) {
                label = R.string.status_empty;
            } else if (row.status == CardStatus.ARCHIVED) {
                label = R.string.status_archived;
            } else if (row.status == CardStatus.EXPIRED) {
                label = R.string.status_expired;
            } else if (row.isExpiringSoon()) {
                label = R.string.status_expiring_soon;
            }

            if (label == null) {
                statusBadge.setVisibility(View.GONE);
                return;
            }
            statusBadge.setText(ctx.getString(label));
            statusBadge.setVisibility(View.VISIBLE);
        }

        /**
         * Draws how much of the card is left.
         *
         * <p>Hidden outright when the starting amount is unknown, which is common — people
         * often only ever learn what is left on a card. A zero-width bar in that case would
         * be a claim that the card is spent, which is a different and much worse thing to
         * say than nothing.
         *
         * <p>A retired card keeps its bar. "This one is empty" is exactly what the archive
         * is there to show, and an archived card with ₪40 still on it is worth being able to
         * tell apart from one with nothing.
         */
        private void bindDepletion(android.content.Context ctx, CardRow row) {
            boolean known = row.initialAmount > 0d;
            depletion.setVisibility(known ? View.VISIBLE : View.GONE);
            if (known) {
                float fraction = CardFace.remainingFraction(row.remaining, row.initialAmount);
                depletion.setProgress(Math.round(fraction * 100f));
                initialAmount.setText(ctx.getString(
                        R.string.of_initial_amount,
                        Formats.money(row.initialAmount, row.currency)));
                initialAmount.setVisibility(View.VISIBLE);
            } else {
                initialAmount.setVisibility(View.GONE);
            }
        }

        /**
         * Sets a retired card back from the live ones.
         *
         * <p>It loses its colour and wears the neutral face instead. That is the whole
         * purpose of the palette: the eight hues are there to help pick a card you are about
         * to spend, so a card you cannot spend should stop competing for that attention.
         *
         * <p>Greying the face replaced dimming the content. Fading the whole card to 55%
         * took its text down with it, which cost contrast on the one group of cards whose
         * balances are already the hardest to justify reading; the grey face keeps white
         * text at full strength and says the same thing more clearly. Elevation still goes:
         * the live cards lift off the page and these lie flat on it, which separates the two
         * groups before any word is read.
         */
        private void bindRetired(CardRow row) {
            boolean retired = row.status.isRetired();

            // Always set every value on both branches, never only on one: a recycled holder
            // arrives carrying whatever the last row left behind, which is how a live card
            // comes back wearing an archived card’s colours.
            content.setBackgroundResource(CardFaces.backgroundFor(row.cardTypeId, row.status));
            // Reset the alpha an earlier design left behind, or a holder recycled from that
            // era stays faded for the life of the list.
            content.setAlpha(1f);

            int strong = retired ? themeColor(com.google.android.material.R.attr.colorOnSurface) : onFace;
            int soft = retired ? themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant) : onFaceVariant;

            title.setTextColor(strong);
            amount.setTextColor(strong);
            subtitle.setTextColor(soft);
            initialAmount.setTextColor(soft);
            expiry.setTextColor(soft);
            matchReason.setTextColor(soft);

            depletion.setProgressDrawable(androidx.core.content.ContextCompat.getDrawable(
                    itemView.getContext(),
                    retired ? R.drawable.progress_depletion_muted
                            : R.drawable.progress_depletion));

            // The badges follow the same split: a translucent white pill only works on a
            // saturated fill, and on the page it would be an invisible smudge.
            int badgeBackground = retired ? R.drawable.bg_badge_muted : R.drawable.bg_face_badge;
            int badgeText = retired
                    ? itemView.getContext().getColor(R.color.card_status) : onFace;
            statusBadge.setBackgroundResource(badgeBackground);
            statusBadge.setTextColor(badgeText);
            warningPill = badgeBackground;
            warning.setTextColor(badgeText);
            onlineBadge.setBackgroundResource(
                    retired ? R.drawable.bg_badge : R.drawable.bg_face_badge);
            onlineBadge.setTextColor(retired
                    ? itemView.getContext().getColor(R.color.online_badge) : onFace);

            // Live cards lift off the page; retired ones lie flat on it. The two groups
            // separate before a single word is read.
            if (itemView instanceof MaterialCardView) {
                ((MaterialCardView) itemView).setCardElevation(retired ? 0f : liveElevation);
            }
        }

        private int themeColor(int attr) {
            return MaterialColors.getColor(itemView, attr);
        }

        /**
         * When the card runs out.
         *
         * <p>No longer coloured. Red and amber cannot survive a background that might be any
         * of eight hues — the amber warning was invisible on the amber face and the red
         * unreadable on the rose one. Urgency moved to the badge in the corner, which reads
         * identically on all of them; this line is left to carry the part a badge cannot,
         * which is the date.
         */
        private void bindExpiry(android.content.Context ctx, CardRow row) {
            if (row.isExpired()) {
                // Naming the month is the difference between "this is over" and being able
                // to tell a card that lapsed last week from one that lapsed in 2023 — which
                // is most of what there is to know about a card in the archive.
                expiry.setText(ctx.getString(R.string.expired_on,
                        Formats.expiryToDisplay(row.expiryDate)));
            } else if (row.isExpiringSoon()) {
                expiry.setText(ctx.getString(R.string.expires_soon, (int) row.daysUntilExpiry));
            } else {
                expiry.setText(ctx.getString(R.string.expires_on,
                        Formats.expiryToDisplay(row.expiryDate)));
            }
        }

        /** Spells out why the card is on screen, naming the merchant that matched. */
        private CharSequence describeMatch(android.content.Context ctx, CardRow row,
                                           int highlightColor) {
            // Naming the card is a request for that card, so report its coverage rather than
            // its merchants: several shops tag themselves "buyme" in their alias lists, and
            // answering "Accepted at MIMI VAZA" to someone looking for their BuyMe card is
            // noise.
            //
            // The test is the card's *name*, not its aliases, because the two are not equally
            // good evidence. Aliases carry issuer names — love_gift_card lists "castro"
            // because Castro Model issues it — and someone typing a shop's name is asking
            // about the shop. Keying off aliases too made that card answer "8 stores" while
            // the other cards on the same screen named the branch.
            if (row.matchedByCardProperName) {
                return row.hasStoreList()
                        ? ctx.getResources().getQuantityString(
                                R.plurals.store_count, row.storeCount, row.storeCount)
                        : ctx.getString(R.string.matched_card_name);
            }
            if (!row.matchedStores.isEmpty()) {
                CharSequence names = joinHighlighted(row.matchedStores, highlightColor);

                // Spliced at a marker rather than concatenated around the shops, so the
                // sentence stays the translator's to word and the highlights survive it —
                // getString would flatten a Spannable argument back to plain text.
                String scaffold = ctx.getString(R.string.accepted_at, StoreLabel.MARKER);
                SpannableStringBuilder text = new SpannableStringBuilder(scaffold);
                int at = scaffold.indexOf(StoreLabel.MARKER);
                if (at >= 0) {
                    text.replace(at, at + StoreLabel.MARKER.length(), names);
                }

                int hidden = row.totalMatchingStores - row.matchedStores.size();
                if (hidden > 0) {
                    text.append(' ').append(ctx.getString(R.string.and_more_stores, hidden));
                }
                return text;
            }
            // Reachable now that an alias-only hit no longer takes the first branch: the
            // query matched something about the card but no merchant. Coverage is the more
            // useful thing to say when there is a list to count.
            if (row.hasStoreList()) {
                return ctx.getResources().getQuantityString(
                        R.plurals.store_count, row.storeCount, row.storeCount);
            }
            if (row.matchedByCardName) {
                return ctx.getString(R.string.matched_card_name);
            }
            return "";
        }

        /**
         * Runs the matched shops together, each with the query picked out inside it.
         *
         * <p>The tint is the answer to "why is this row here?" for a query that landed on a
         * spelling rather than a name — searching קרולינה returns a Castro-group entry that
         * reads as noise until the two words that matched are the two words that stand out.
         * Bolded as well as tinted, because colour alone is not a signal everyone receives.
         */
        private static CharSequence joinHighlighted(List<StoreLabel> labels, int highlightColor) {
            SpannableStringBuilder out = new SpannableStringBuilder();
            for (StoreLabel label : labels) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                int base = out.length();
                out.append(label.text);
                if (!label.hasHighlight()) {
                    continue;
                }
                int from = base + label.highlightStart;
                int to = base + label.highlightEnd;
                out.setSpan(new StyleSpan(Typeface.BOLD), from, to,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(highlightColor), from, to,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return out;
        }
    }
}
