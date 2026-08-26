package com.mycards.ui.stores;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.mycards.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one card's merchants, each saying why the current query put it on screen.
 *
 * <p>Rows are deliberately not clickable. This is a reference list — there is nothing on the
 * other side of a tap, and an affordance leading nowhere is worse than none. A long press
 * copies the name, which is the one thing people actually want to take away from it.
 */
class StoreRowAdapter extends RecyclerView.Adapter<StoreRowAdapter.VH> {

    interface OnStoreLongClick {
        void onCopy(String name);
    }

    private final List<StoreRow> items = new ArrayList<>();
    private final OnStoreLongClick longClick;
    private final int highlightColor;

    StoreRowAdapter(int highlightColor, OnStoreLongClick longClick) {
        this.highlightColor = highlightColor;
        this.longClick = longClick;
    }

    /**
     * Replaces the whole list.
     *
     * <p>No {@code DiffUtil} on purpose. Every keystroke replaces the results wholesale, and
     * an animated diff across a thousand rows reads as a shuffle rather than a filter — on
     * top of which the list is scrolled back to the top anyway, so nothing survives to be
     * animated in place.
     */
    void submit(List<StoreRow> rows) {
        items.clear();
        if (rows != null) {
            items.addAll(rows);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_store_row, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(items.get(position), highlightColor, longClick);
    }

    static class VH extends RecyclerView.ViewHolder {

        private final TextView name;
        private final TextView alias;
        private final TextView onlineBadge;

        VH(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.storeName);
            alias = itemView.findViewById(R.id.storeAlias);
            onlineBadge = itemView.findViewById(R.id.storeOnlineBadge);
        }

        void bind(StoreRow row, int highlightColor, OnStoreLongClick longClick) {
            name.setText(highlighted(row, highlightColor));

            if (row.matchedAlias == null) {
                alias.setVisibility(View.GONE);
            } else {
                alias.setVisibility(View.VISIBLE);
                alias.setText(itemView.getContext()
                        .getString(R.string.store_also_called, row.matchedAlias));
            }

            onlineBadge.setVisibility(row.online ? View.VISIBLE : View.GONE);

            itemView.setOnLongClickListener(v -> {
                longClick.onCopy(row.name);
                return true;
            });
        }

        /**
         * Bolds and tints the stretch of the name the query matched.
         *
         * <p>The span is bounds-checked rather than trusted. It is computed from an offset
         * map back through normalization, and a name this adapter cannot draw is far better
         * than a crash in a view holder — so anything out of range simply loses its
         * highlight.
         */
        private static CharSequence highlighted(StoreRow row, int highlightColor) {
            if (!row.hasHighlight()
                    || row.highlightEnd > row.name.length()) {
                return row.name;
            }
            SpannableString styled = new SpannableString(row.name);
            styled.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                    row.highlightStart, row.highlightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(highlightColor),
                    row.highlightStart, row.highlightEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return styled;
        }
    }
}
