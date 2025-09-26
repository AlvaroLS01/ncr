package com.comerzzia.dinosol.pos.ncr.services.virtualmoney.restrictions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.lang3.StringUtils;

public class RestrictionGroups {

	public static final String ITEMS_KEY = "I";
	public static final String CATEGORIES_KEY = "C";
	public static final String FAMILIES_KEY = "F";
	public static final String RESTRICTIONS_KEY = "R";
	public static final String STORES_KEY = "S";

	public static final String SYNTAX_ERRORS_KEY = "SYNTAXERRORS";

	protected final List<String> validGroups = Arrays.asList(ITEMS_KEY, CATEGORIES_KEY, FAMILIES_KEY, STORES_KEY, RESTRICTIONS_KEY);

	public class RestrictionGroup {

		protected String groupKey;
		protected List<String> include = new ArrayList<String>();
		protected List<String> exclude = new ArrayList<String>();

		public RestrictionGroup(String groupKey) {
			this.groupKey = groupKey;
		}

		public String getGroupKey() {
			return groupKey;
		}

		public List<String> getInclude() {
			return include;
		}

		public List<String> getExclude() {
			return exclude;
		}

		public void removeDuplicates() {
			include = new ArrayList<String>(new HashSet<String>(include));
			exclude = new ArrayList<String>(new HashSet<String>(exclude));
		}
	}

	protected Map<String, RestrictionGroup> groups = new HashMap<String, RestrictionGroup>();

	public RestrictionGroups() {
	}

	public RestrictionGroups(String restrictionString) {
		parse(restrictionString);
	}

	public void parse(String restrictionString) {
		groups.clear();

		StringTokenizer tokens = new StringTokenizer(restrictionString, "+-", true);
		String currentOperator = "+";

		while (tokens.hasMoreTokens()) {
			String token = tokens.nextToken().toUpperCase();

			if (StringUtils.equals("+", token)) {
				currentOperator = "+";
			}
			else if (StringUtils.equals("-", token)) {
				currentOperator = "-";
			}
			else if (StringUtils.equals("T", token)) {
				// include all categories
				RestrictionGroup group = groups.get(CATEGORIES_KEY);

				if (group == null) {
					group = new RestrictionGroup(CATEGORIES_KEY);
				}

				group.include.add("*");

				groups.put(CATEGORIES_KEY, group);
			}
			else if (token.isEmpty()) {
				continue;
			}
			else {
				// parse group from token
				String groupString = "I"; // default group

				int groupIndicator = token.indexOf(":");

				if (groupIndicator > -1) {
					// parse group and item
					groupString = StringUtils.substringBefore(token, ":");

					if (!validGroups.contains(groupString) || StringUtils.substringAfter(token, ":").isEmpty() || StringUtils.countMatches(token, ":") > 1) {
						groupString = SYNTAX_ERRORS_KEY;
					}
					else {
						token = StringUtils.trim(StringUtils.substringAfter(token, ":"));
					}
				}

				// get group
				RestrictionGroup group = groups.get(groupString);

				if (group == null) {
					group = new RestrictionGroup(groupString);
				}

				if (currentOperator.equals("+")) {
					group.include.add(token);
				}
				else {
					group.exclude.add(token);
				}

				// update group
				groups.put(groupString, group);
			}
		}
	}

	public RestrictionGroup getGroup(String groupKey) {
		return groups.get(groupKey);
	}

	public Boolean hasErrors() {
		return groups.get(SYNTAX_ERRORS_KEY) != null;
	}

	protected void mergeGroup(RestrictionGroup group) {
		if (group == null)
			return;

		RestrictionGroup destinationGroup = getGroup(group.getGroupKey());

		if (destinationGroup == null) {
			destinationGroup = new RestrictionGroup(group.getGroupKey());
		}

		destinationGroup.getInclude().addAll(group.getInclude());
		destinationGroup.getExclude().addAll(group.getExclude());
		destinationGroup.removeDuplicates();

		groups.put(destinationGroup.getGroupKey(), destinationGroup);
	}

	public void mergeFrom(RestrictionGroups restrictionGroups) {
		if (restrictionGroups == null)
			return;

		mergeGroup(restrictionGroups.getGroup(ITEMS_KEY));
		mergeGroup(restrictionGroups.getGroup(CATEGORIES_KEY));
		mergeGroup(restrictionGroups.getGroup(FAMILIES_KEY));
		mergeGroup(restrictionGroups.getGroup(STORES_KEY));
	}
}
