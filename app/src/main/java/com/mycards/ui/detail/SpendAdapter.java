package com.mycards.ui.detail;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.mycards.R;
import com.mycards.data.db.SpendEntity;
import com.mycards.ui.Formats;

public class SpendAdapter extends ListAdapter<SpendEntity, SpendAdapter.VH> {

    public interface OnSpendLongClick {
        void onLongClick(SpendEntity spend);
    }

    private final String currency;
    private final OnSpendLongClick listener;

    public SpendAdapter(String currency, OnSpendLongClick listener) {
        super(DIFF);
        this.currency = currency;
        this.listener = listener;
    }

    private static final DiffUtil.ItemCallback<SpendEntity> DIFF =
            new DiffUtil.ItemCallback<SpendEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull SpendEntity a, @NonNull SpendEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull SpendEntity a, @NonNull SpendEntity b) {
                    return a.amount == b.amount
                            && a.title.equals(b.title)
                            && a.spentAt == b.spentAt;
                }
            };

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_spend, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position), currency, listener);
    }

    static class VH extends RecyclerView.ViewHolder {

        private final TextView title;
        private final TextView meta;
        private final TextView amount;

        VH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.spendTitle);
            meta = itemView.findViewById(R.id.spendMeta);
            amount = itemView.findViewById(R.id.spendAmount);
        }

        void bind(SpendEntity spend, String currency, OnSpendLongClick listener) {
            title.setText(spend.title);

            StringBuilder sub = new StringBuilder(
                    Formats.prettyDate(itemView.getContext(), spend.spentAt));
            if (spend.storeName != null && !spend.storeName.trim().isEmpty()) {
                sub.append(" · ").append(spend.storeName);
            }
            if (SpendEntity.SOURCE_RECONCILIATION.equals(spend.source)) {
                // Worth distinguishing: this entry was reconstructed from a balance gap,
                // not observed at the time of purchase.
                sub.append(" · ").append(
                        itemView.getContext().getString(R.string.reconcile_title));
            }
            meta.setText(sub.toString());

            // No minus sign: everything in this list is a deduction, so the symbol adds
            // nothing and reads oddly next to a right-to-left shekel sign.
            amount.setText(Formats.money(spend.amount, currency));
            itemView.setOnLongClickListener(v -> {
                listener.onLongClick(spend);
                return true;
            });
        }
    }
}
