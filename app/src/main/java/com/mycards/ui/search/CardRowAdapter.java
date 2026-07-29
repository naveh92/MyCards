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

            // Expiry doubles as a nudge: a card about to lapse should stand out.
            if (row.isExpired()) {
                expiry.setText(ctx.getString(R.string.expired));
                expiry.setTextColor(ctx.getColor(R.color.expiry_expired));
            } else if (row.isExpiringSoon()) {
                expiry.setText(ctx.getString(R.string.expires_soon, (int) row.daysUntilExpiry));
                expiry.setTextColor(ctx.getColor(R.color.expiry_warning));
            } else if (row.expiryDate != null && !row.expiryDate.isEmpty()) {
                expiry.setText(ctx.getString(R.string.expires_on,
                        Formats.expiryToDisplay(row.expiryDate)));
                expiry.setTextColor(matchReason.getCurrentTextColor());
                expiry.setAlpha(0.75f);
            } else {
                expiry.setText(ctx.getString(R.string.no_expiry));
                expiry.setAlpha(0.75f);
            }

            matchReason.setText(describeMatch(ctx, row));
            // The badge describes the merchants listed above it, so it only makes sense
            // when merchants are actually what is being shown.
            onlineBadge.setVisibility(
                    row.hasOnlineMatch && !row.matchedByCardName ? View.VISIBLE : View.GONE);

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

        /** Spells out why the card is on screen, naming the merchant that matched. */
        private String describeMatch(android.content.Context ctx, CardRow row) {
            // Searching the card's own name is a request for that card, so report its
            // coverage. Listing merchants whose aliases incidentally contain "buyme"
            // would be pure noise here.
            if (row.matchedByCardName) {
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
            if (row.matchedByCardName) {
                return ctx.getString(R.string.matched_card_name);
            }
            if (row.hasStoreList()) {
                return ctx.getString(R.string.card_count_stores, row.storeCount);
            }
            return "";
        }
    }
}
