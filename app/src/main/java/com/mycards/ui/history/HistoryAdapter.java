package com.mycards.ui.history;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.mycards.R;
import com.mycards.data.db.SpendEntity;
import com.mycards.ui.CardFaces;
import com.mycards.ui.Formats;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The spending history list: a heading per month, and the purchases under it. */
public class HistoryAdapter extends ListAdapter<HistoryRow, RecyclerView.ViewHolder> {

    public interface OnPurchaseAction {
        void onEdit(HistoryRow row);

        void onDelete(HistoryRow row);

        /** Open the card a purchase was charged to. */
        void onOpenCard(HistoryRow row);
    }

    private final OnPurchaseAction listener;

    public HistoryAdapter(OnPurchaseAction listener) {
        super(DIFF);
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<HistoryRow> DIFF =
            new DiffUtil.ItemCallback<HistoryRow>() {
                @Override
                public boolean areItemsTheSame(@NonNull HistoryRow a, @NonNull HistoryRow b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull HistoryRow a, @NonNull HistoryRow b) {
                    if (a.type != b.type) {
                        return false;
                    }
                    if (a.type == HistoryRow.TYPE_MONTH) {
                        // The total moves as a search narrows what the month contains.
                        return a.monthTotal == b.monthTotal
                                && equal(a.monthCurrency, b.monthCurrency);
                    }
                    return a.spend.amount == b.spend.amount
                            && a.spend.spentAt == b.spend.spentAt
                            && a.spend.title.equals(b.spend.title)
                            && equal(a.spend.storeName, b.spend.storeName)
                            && equal(a.cardName, b.cardName);
                }

                private boolean equal(String a, String b) {
                    return a == null ? b == null : a.equals(b);
                }
            };

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == HistoryRow.TYPE_MONTH) {
            return new MonthVH(inflater.inflate(R.layout.item_history_month, parent, false));
        }
        return new PurchaseVH(inflater.inflate(R.layout.item_history_entry, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        HistoryRow row = getItem(position);
        if (holder instanceof MonthVH) {
            ((MonthVH) holder).bind(row);
        } else {
            ((PurchaseVH) holder).bind(row, listener);
        }
    }

    static class MonthVH extends RecyclerView.ViewHolder {

        private final TextView month;
        private final TextView total;

        MonthVH(@NonNull View itemView) {
            super(itemView);
            month = itemView.findViewById(R.id.monthLabel);
            total = itemView.findViewById(R.id.monthTotal);
        }

        void bind(HistoryRow row) {
            month.setText(formatMonth(itemView.getContext(), row.monthStart));

            // A month whose purchases are in different currencies gets no total, because any
            // single number would be adding shekels to euros and stating the result.
            if (row.monthCurrency == null) {
                total.setVisibility(View.GONE);
            } else {
                total.setVisibility(View.VISIBLE);
                total.setText(Formats.money(row.monthTotal, row.monthCurrency));
            }
        }

        /**
         * "September 2026", in whatever order and wording the reader's language puts it.
         *
         * <p>{@code getBestDateTimePattern} rather than a hardcoded "MMMM yyyy": Hebrew wants
         * the same two fields arranged differently, and asking the platform for the pattern
         * is the only way to get that without maintaining a list of them.
         */
        private String formatMonth(Context context, long millis) {
            Locale locale = context.getResources().getConfiguration().getLocales().get(0);
            String pattern = android.text.format.DateFormat
                    .getBestDateTimePattern(locale, "yMMMM");
            return new SimpleDateFormat(pattern, locale).format(new Date(millis));
        }
    }

    static class PurchaseVH extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView meta;
        private final TextView card;
        private final TextView amount;
        private final View cardMarker;

        PurchaseVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.entryTitle);
            meta = itemView.findViewById(R.id.entryMeta);
            card = itemView.findViewById(R.id.entryCard);
            amount = itemView.findViewById(R.id.entryAmount);
            cardMarker = itemView.findViewById(R.id.entryCardMarker);
        }

        void bind(HistoryRow row, OnPurchaseAction listener) {
            Context ctx = itemView.getContext();
            SpendEntity spend = row.spend;

            title.setText(spend.title);

            // The paying card’s colour, so a run of purchases off one card is visible
            // before a word of it is read. Decorative: the card is named in full below, and
            // this is not the only way to tell one row from another.
            cardMarker.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    CardFaces.accentFor(ctx, row.cardTypeId, row.faceColor)));

            StringBuilder sub = new StringBuilder(Formats.prettyDate(ctx, spend.spentAt));
            if (spend.storeName != null && !spend.storeName.trim().isEmpty()) {
                sub.append(" · ").append(spend.storeName);
            }
            if (SpendEntity.SOURCE_RECONCILIATION.equals(spend.source)) {
                // Worth distinguishing here for the same reason it is on the card screen:
                // this entry was reconstructed from a balance gap, not observed at the time.
                sub.append(" · ").append(ctx.getString(R.string.reconcile_title));
            }
            meta.setText(sub.toString());

            // The one thing this screen says that the card's own log cannot, so it gets a
            // line of its own rather than being appended to the run of metadata above.
            card.setText(row.cardName);
            // Tapping the name goes to the card; tapping anywhere else in the row still
            // edits the purchase. The child listener consumes the touch, so the two do not
            // fight over it.
            card.setOnClickListener(v -> listener.onOpenCard(row));
            // Renames the tap for a screen reader, which would otherwise read out the card
            // name and leave what tapping it does to guesswork.
            ViewCompat.replaceAccessibilityAction(card,
                    AccessibilityActionCompat.ACTION_CLICK,
                    ctx.getString(R.string.history_open_card), null);

            // No minus sign: every line here is a deduction, so the symbol adds nothing and
            // reads oddly beside a right-to-left shekel sign.
            amount.setText(Formats.money(spend.amount, row.currency));

            // The same two gestures as the card screen's log, so they are learned once.
            itemView.setOnClickListener(v -> listener.onEdit(row));
            itemView.setOnLongClickListener(v -> {
                listener.onDelete(row);
                return true;
            });
        }
    }
}
