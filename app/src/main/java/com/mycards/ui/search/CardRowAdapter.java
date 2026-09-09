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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.mycards.R;
import com.mycards.cards.CardStatus;
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
                    && a.status == b.status
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
        private final TextView expiry;
        private final TextView matchReason;
        private final TextView onlineBadge;
        private final TextView warning;
        private final TextView statusBadge;

        /** Everything inside the card, which is what gets dimmed when the card is retired. */
        private final View content;

        /** The lift a live card has in the layout, kept so a retired one can be given it back. */
        private final float liveElevation;

        VH(@NonNull View itemView) {
            super(itemView);
            content = itemView.findViewById(R.id.cardContent);
            liveElevation = itemView instanceof MaterialCardView
                    ? ((MaterialCardView) itemView).getCardElevation()
                    : 0f;
            title = itemView.findViewById(R.id.cardTitle);
            subtitle = itemView.findViewById(R.id.cardSubtitle);
            amount = itemView.findViewById(R.id.amount);
            expiry = itemView.findViewById(R.id.expiry);
            matchReason = itemView.findViewById(R.id.matchReason);
            onlineBadge = itemView.findViewById(R.id.onlineBadge);
            warning = itemView.findViewById(R.id.warning);
            statusBadge = itemView.findViewById(R.id.statusBadge);
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

            // Nothing to say when the card has no expiry, so say nothing rather than
            // spending a line telling the user something is absent.
            if (row.expiryDate == null || row.expiryDate.trim().isEmpty()) {
                expiry.setVisibility(View.GONE);
            } else {
                expiry.setVisibility(View.VISIBLE);
                bindExpiry(ctx, row);
            }

            CharSequence reason = describeMatch(ctx, row, highlightColor);
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

            if (row.hasUnreconciledMismatch) {
                warning.setText(ctx.getString(R.string.unlogged_transaction_title));
                warning.setVisibility(View.VISIBLE);
            } else if (!row.hasStoreList()) {
                warning.setText(ctx.getString(R.string.store_list_unavailable));
                warning.setVisibility(View.VISIBLE);
            } else {
                warning.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> listener.onCard(row));
        }

        /**
         * States why a card is in the archive, when nothing else on the row would.
         *
         * <p>Expiry is the exception: the line below already reports it, in red and with the
         * month it happened.
         *
         * <p>Expiry used to be left to that line alone, to avoid saying the same word twice
         * in one row. Seen next to its neighbours that was the wrong trade: three cards sat
         * in the archive, two wearing a badge and one bare, and the odd one out read as an
         * oversight rather than as a decision. A set of states is only legible as a set when
         * every member is marked the same way — so the word is worth repeating, and the line
         * below still carries the part the badge does not, which is when.
         */
        private void bindStatus(android.content.Context ctx, CardRow row) {
            Integer label = null;
            if (row.status == CardStatus.EMPTY) {
                label = R.string.status_empty;
            } else if (row.status == CardStatus.ARCHIVED) {
                label = R.string.status_archived;
            } else if (row.status == CardStatus.EXPIRED) {
                label = R.string.status_expired;
            }

            if (label == null) {
                statusBadge.setVisibility(View.GONE);
                return;
            }
            statusBadge.setText(ctx.getString(label));
            statusBadge.setVisibility(View.VISIBLE);
        }

        /**
         * Sets a retired card back from the live ones.
         *
         * <p>A card in the archive is still readable, still openable and still holds whatever
         * is on it — so this is a step back, not the 38% that Material calls "disabled" and
         * that would make a balance genuinely hard to read. Elevation goes too: the live
         * cards lift off the page and these lie flat on it, which separates the two groups
         * even before any word is read.
         */
        private void bindRetired(CardRow row) {
            boolean retired = row.status.isRetired();
            // Always set both, never only on the retired branch: a recycled holder carries
            // whatever the last row left behind, which is how a live card comes back faded.
            //
            // The alpha goes on the content and not on itemView, because RecyclerView's item
            // animator treats itemView's alpha as its own and restores it to 1 when an add or
            // change animation finishes — which it did here, silently, leaving the dimming in
            // the code and absent from the screen.
            content.setAlpha(retired ? 0.55f : 1f);
            if (itemView instanceof MaterialCardView) {
                ((MaterialCardView) itemView).setCardElevation(retired ? 0f : liveElevation);
            }
        }

        /** Expiry doubles as a nudge: a card about to lapse should stand out. */
        private void bindExpiry(android.content.Context ctx, CardRow row) {
            // Reset first. Only the last branch dims the line, and a holder that took that
            // branch keeps the alpha when it is recycled — which is how a scrolled-past
            // "Expires in 6 days" came back as a faded warning in a fresh row.
            expiry.setAlpha(1f);

            if (row.isExpired()) {
                // Naming the month is the difference between "this is over" and being able
                // to tell a card that lapsed last week from one that lapsed in 2023 — which
                // is most of what there is to know about a card in the archive.
                expiry.setText(ctx.getString(R.string.expired_on,
                        Formats.expiryToDisplay(row.expiryDate)));
                expiry.setTextColor(ctx.getColor(R.color.expiry_expired));
            } else if (row.isExpiringSoon()) {
                expiry.setText(ctx.getString(R.string.expires_soon, (int) row.daysUntilExpiry));
                expiry.setTextColor(ctx.getColor(R.color.expiry_warning));
            } else {
                expiry.setText(ctx.getString(R.string.expires_on,
                        Formats.expiryToDisplay(row.expiryDate)));
                expiry.setTextColor(matchReason.getCurrentTextColor());
                expiry.setAlpha(0.75f);
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
