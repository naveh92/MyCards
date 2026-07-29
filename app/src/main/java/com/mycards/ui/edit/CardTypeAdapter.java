package com.mycards.ui.edit;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

import com.mycards.data.catalog.model.CardTypeDef;
import com.mycards.search.SearchNormalizer;
import com.mycards.search.SearchEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * Dropdown adapter for the card-type picker that filters as you type.
 *
 * <p>Uses the same forgiving matching as the main search rather than the stock prefix
 * filter, so "chef" finds "BuyMe Chef", "buyme" narrows to the BuyMe family, and Hebrew or
 * a wrong keyboard layout works too. With a catalog of dozens of card types, scrolling a
 * flat list to find the right one is exactly the friction this app exists to remove.
 */
public class CardTypeAdapter extends ArrayAdapter<CardTypeAdapter.Option> {

    /** One selectable card type, with its searchable spellings precomputed. */
    public static class Option {

        public final CardTypeDef def;
        public final String label;
        private final List<String> haystacks = new ArrayList<>();

        public Option(CardTypeDef def, String label) {
            this.def = def;
            this.label = label;

            addNormalized(label);
            for (String name : def.allSearchableNames()) {
                addNormalized(name);
            }
            if (def.issuer != null) {
                addNormalized(def.issuer);
            }
        }

        private void addNormalized(String raw) {
            String n = SearchNormalizer.normalize(raw);
            if (!n.isEmpty() && !haystacks.contains(n)) {
                haystacks.add(n);
            }
        }

        boolean matches(List<String> normalizedVariants) {
            for (String variant : normalizedVariants) {
                for (String hay : haystacks) {
                    if (SearchNormalizer.containsNormalized(hay, variant)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }

    /** The unfiltered set; the superclass's list is replaced as the user types. */
    private final List<Option> master;

    public CardTypeAdapter(@NonNull Context context, @NonNull List<Option> options) {
        super(context, android.R.layout.simple_list_item_1, new ArrayList<>(options));
        this.master = new ArrayList<>(options);
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return filter;
    }

    private final Filter filter = new Filter() {

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            List<Option> matched = new ArrayList<>();

            List<String> variants = SearchEngine.queryVariants(
                    constraint == null ? "" : constraint.toString());

            if (variants.isEmpty()) {
                // Empty box means "show me everything", not "show me nothing".
                matched.addAll(master);
            } else {
                for (Option option : master) {
                    if (option.matches(variants)) {
                        matched.add(option);
                    }
                }
            }

            FilterResults results = new FilterResults();
            results.values = matched;
            results.count = matched.size();
            return results;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence constraint, FilterResults results) {
            clear();
            if (results.values != null) {
                addAll((List<Option>) results.values);
            }
            notifyDataSetChanged();
        }

        @Override
        public CharSequence convertResultToString(Object resultValue) {
            // Without this the field would be filled with Object.toString() on selection.
            return resultValue == null ? "" : ((Option) resultValue).label;
        }
    };
}
