package com.mycards.ui.search;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.mycards.R;
import com.mycards.search.Store;
import com.mycards.ui.Formats;

import java.util.ArrayList;
import java.util.List;

public class CardRowAdapter extends ListAdapter<CardRow, CardRowAdapter.VH> {

    public interface OnCardClick {
        void onCard(CardRow row);
    }

    private final OnCardClick listener;

    public CardRowAdapter(OnCardClick listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<CardRow> DIFF = new DiffUtil.ItemCallback<CardRow>() {
        @Override
        public boolean areItemsTheSame(@NonNull CardRow a, @NonNull CardRow b) {
            return a.cardId == b.cardId;
        }

        @Override
        public boolean areContentsTheSame(@NonNull CardRow a, @NonNull CardRow b) {
            return a.score == b.score
                    && a.remaining == b.remaining
                    && a.matchedByCardName == b.matchedByCardName
                    && a.hasUnreconciledMismatch == b.hasUnreconciledMismatch
                    && a.matchedStores.size() == b.matchedStores.size()
                    && storeNames(a).equals(storeNames(b));
        }

        private List<String> storeNames(CardRow row) {
            List<String> names = new ArrayList<>();
            for (Store s : row.matchedStores) {
                names.add(s.getName());
            }
            return names;
        }
    };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position), listener);
    }

    static class VH extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView subtitle;
        private final TextView amount;
        private final TextView expiry;
        private final TextView matchReason;
        private final TextView onlineBadge;
        private final TextView warning;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.cardTitle);
            subtitle = itemView.findViewById(R.id.cardSubtitle);
            amount = itemView.findViewById(R.id.amount);
            expiry = itemView.findViewById(R.id.expiry);
            matchReason = itemView.findViewById(R.id.matchReason);
            onlineBadge = itemView.findViewById(R.id.onlineBadge);
            warning = itemView.findViewById(R.id.warning);
        }

        void bind(CardRow row, OnCardClick listener) {
            android.content.Context ctx = itemView.getContext();

            title.setText(row.title);
            if (row.subtitle != null) {
                subtitle.setText(row.subtitle);
                subtitle.setVisibility(View.VISIBLE);
            } else {
                subtitle.setVisibility(View.GONE);
            }

            amount.setText(Formats.money(row.remaining, row.currency));

            // Nothing to say when the card has no expiry, so say nothing rather than
            // spending a line telling the user something is absent.
            if (row.expiryDate == null || row.expiryDate.trim().isEmpty()) {
                expiry.setVisibility(View.GONE);
            } else {
                expiry.setVisibility(View.VISIBLE);
                bindExpiry(ctx, row);
            }

            matchReason.setText(describeMatch(ctx, row));

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

        /** Expiry doubles as a nudge: a card about to lapse should stand out. */
        private void bindExpiry(android.content.Context ctx, CardRow row) {
            if (row.isExpired()) {
                expiry.setText(ctx.getString(R.string.expired));
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
        private String describeMatch(android.content.Context ctx, CardRow row) {
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
                        ? ctx.getString(R.string.card_count_stores, row.storeCount)
                        : ctx.getString(R.string.matched_card_name);
            }
            if (!row.matchedStores.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < row.matchedStores.size(); i++) {
                    if (i > 0) {
                        names.append(", ");
                    }
                    names.append(row.matchedStores.get(i).getName());
                }
                String text = ctx.getString(R.string.accepted_at, names.toString());

                int hidden = row.totalMatchingStores - row.matchedStores.size();
                if (hidden > 0) {
                    text += " " + ctx.getString(R.string.and_more_stores, hidden);
                }
                return text;
            }
            // Reachable now that an alias-only hit no longer takes the first branch: the
            // query matched something about the card but no merchant. Coverage is the more
            // useful thing to say when there is a list to count.
            if (row.hasStoreList()) {
                return ctx.getString(R.string.card_count_stores, row.storeCount);
            }
            if (row.matchedByCardName) {
                return ctx.getString(R.string.matched_card_name);
            }
            return "";
        }
    }
}
