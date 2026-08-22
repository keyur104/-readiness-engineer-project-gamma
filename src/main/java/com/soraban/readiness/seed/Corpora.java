package com.soraban.readiness.seed;

import java.util.List;

/**
 * Word lists for generated names, addresses, and memos.
 *
 * <h2>Why these are committed constants rather than a faker library</h2>
 *
 * <p>The generator's contract is that {@code --seed=42} produces a byte-identical corpus
 * forever. A faker dependency breaks that contract the first time it ships a data update:
 * the code is unchanged, the seed is unchanged, and the corpus is different. Golden tests
 * fail, planted fixtures move, and the cause is invisible in the diff.
 *
 * <p>Committing the lists makes the data part of the repository, so a change to it is a
 * reviewable commit like any other.
 *
 * <p>The lists are deliberately mixed in character &mdash; company-style names, trade
 * names, and personal names &mdash; because a real accounts-payable ledger contains all
 * three, and a sole proprietor paid under their own name is exactly the vendor whose TIN
 * is an SSN. That is the population the security design is really about.
 */
public final class Corpora {

    private Corpora() {
    }

    /** Company-name leading words. Combined with {@link #TRADES} and a legal suffix. */
    public static final List<String> COMPANY_PREFIXES = List.of(
            "Acme", "Summit", "Riverbend", "Cedar Ridge", "Northgate", "Blue Ridge", "Ironwood",
            "Stonebridge", "Fairview", "Lakeshore", "Highland", "Copper Creek", "Willow Park",
            "Granite", "Silverline", "Redwood", "Bayside", "Clearwater", "Foxglove", "Harborview",
            "Juniper", "Kestrel", "Larkspur", "Maplewood", "Northstar", "Oakfield", "Pinecrest",
            "Quarry Hill", "Rosewood", "Sandstone", "Timberline", "Vantage", "Westbrook", "Yellowstone",
            "Alder", "Birchwood", "Canyon", "Dovetail", "Eastgate", "Firelight", "Goldenrod",
            "Hawthorne", "Inlet", "Jasper", "Kingsley", "Limestone", "Meridian", "Nightingale",
            "Orchard", "Prairie", "Quicksilver", "Ravenwood", "Sagebrook", "Thornfield", "Umberton",
            "Valeport", "Wexford", "Yarrow", "Ashcroft", "Bellhaven", "Coldwater", "Dunmore",
            "Elmhurst", "Fenwick", "Glenmore", "Hollybrook", "Ivywood", "Kirkwood", "Langley",
            "Marbury", "Norwood", "Oakhaven", "Pemberton", "Rockford", "Stanton", "Thatcher",
            "Vernon", "Whitfield", "Ashland", "Brookfield", "Carlisle"
    );

    /** The trade or discipline. This is what makes a name read as a real AP vendor. */
    public static final List<String> TRADES = List.of(
            "Plumbing", "Electric", "Landscaping", "Consulting", "Contracting", "Roofing",
            "Painting", "Carpentry", "Masonry", "Flooring", "Drywall", "HVAC", "Welding",
            "Excavation", "Paving", "Fencing", "Glazing", "Insulation", "Ironworks", "Surveying",
            "Engineering", "Architecture", "Design Studio", "Marketing", "Bookkeeping", "Legal Services",
            "IT Services", "Web Design", "Photography", "Catering", "Janitorial", "Security Services",
            "Logistics", "Freight", "Staffing", "Translation", "Training", "Appraisal"
    );

    /** Legal suffixes, applied to only some names so the normalizer has real variety to fold. */
    public static final List<String> LEGAL_SUFFIXES = List.of(
            "LLC", "Inc.", "Inc", "L.L.C.", "Co.", "Corp.", "Ltd.", "LLP", "PLLC", "PC"
    );

    /** Given names. Used for sole proprietors, whose TIN is an SSN. */
    public static final List<String> FIRST_NAMES = List.of(
            "James", "Maria", "Robert", "Linda", "Michael", "Patricia", "David", "Jennifer",
            "William", "Elizabeth", "Richard", "Barbara", "Joseph", "Susan", "Thomas", "Jessica",
            "Charles", "Sarah", "Daniel", "Karen", "Matthew", "Nancy", "Anthony", "Lisa",
            "Mark", "Margaret", "Donald", "Betty", "Steven", "Sandra", "Paul", "Ashley",
            "Andrew", "Dorothy", "Joshua", "Kimberly", "Kenneth", "Emily", "Kevin", "Donna",
            "Brian", "Michelle", "George", "Carol", "Timothy", "Amanda", "Ronald", "Melissa",
            "Jason", "Deborah", "Edward", "Stephanie", "Jeffrey", "Rebecca", "Ryan", "Laura",
            "Jacob", "Sharon", "Gary", "Cynthia", "Nicholas", "Kathleen", "Eric", "Amy",
            "Jonathan", "Angela", "Stephen", "Shirley", "Larry", "Anna", "Justin", "Ruth",
            "Scott", "Brenda", "Rosa", "Hector", "Priya", "Wei", "Amara", "Tomasz", "Ines", "Yusuf"
    );

    /** Surnames. Deliberately includes non-ASCII forms so the NFKD path is exercised by real data. */
    public static final List<String> LAST_NAMES = List.of(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
            "Thomas", "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson", "White",
            "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson", "Walker", "Young",
            "Allen", "King", "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores", "Green",
            "Adams", "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell", "Carter",
            "Roberts", "Nunez", "Ortega", "Delgado", "Muller", "Okafor", "Kowalski", "Petrov",
            "Yamamoto", "Kaur", "Silva", "Costa", "Larsen", "Novak", "Haddad", "Ibrahim",
            "Kim", "Chen", "Patel", "Singh", "Ali", "Dubois", "Rossi", "Schneider", "Vargas"
    );

    /**
     * Surnames carrying diacritics.
     *
     * <p>Present on purpose. The normalizer's NFKD step exists so that {@code "Núñez"} and
     * {@code "Nunez"} resolve to the same vendor, and a corpus with no accented names would
     * never exercise it &mdash; the code would look tested and be untested.
     */
    public static final List<String> ACCENTED_LAST_NAMES = List.of(
            "Núñez", "José", "Müller", "Håkansson", "Łukasiewicz", "Öztürk", "Šimić",
            "González", "Martínez", "Hernández", "Peña", "Ibáñez", "Cruz Rodríguez"
    );

    public static final List<String> STREET_NAMES = List.of(
            "Main", "Oak", "Pine", "Maple", "Cedar", "Elm", "Washington", "Lake", "Hill", "Walnut",
            "Spring", "Ridge", "River", "Church", "Park", "Franklin", "Chestnut", "Highland",
            "Jefferson", "Madison", "Adams", "Lincoln", "Sunset", "Willow", "Birch", "Broadway"
    );

    public static final List<String> STREET_TYPES = List.of(
            "St", "Ave", "Rd", "Blvd", "Dr", "Ln", "Way", "Ct", "Pl", "Ter"
    );

    /** City and state paired, so generated addresses are internally consistent. */
    public static final List<String[]> CITIES = List.of(
            new String[]{"Portland", "OR"}, new String[]{"Austin", "TX"},
            new String[]{"Denver", "CO"}, new String[]{"Columbus", "OH"},
            new String[]{"Raleigh", "NC"}, new String[]{"Boise", "ID"},
            new String[]{"Madison", "WI"}, new String[]{"Tucson", "AZ"},
            new String[]{"Richmond", "VA"}, new String[]{"Spokane", "WA"},
            new String[]{"Fresno", "CA"}, new String[]{"Omaha", "NE"},
            new String[]{"Tulsa", "OK"}, new String[]{"Akron", "OH"},
            new String[]{"Salem", "OR"}, new String[]{"Provo", "UT"},
            new String[]{"Reno", "NV"}, new String[]{"Mobile", "AL"},
            new String[]{"Peoria", "IL"}, new String[]{"Scranton", "PA"}
    );

    /** Client business names. Deliberately distinct in style from vendor names. */
    public static final List<String> CLIENT_PREFIXES = List.of(
            "Harbor", "Pinnacle", "Cornerstone", "Evergreen", "Beacon", "Anchor", "Compass",
            "Trellis", "Lantern", "Foundry", "Wheelhouse", "Keystone", "Almanac", "Bellweather",
            "Cartwright", "Dockside", "Ember", "Falcon", "Gallery", "Hearth", "Ironclad",
            "Junction", "Kindred", "Lighthouse", "Millstone", "Northfield", "Overlook",
            "Provision", "Quarry", "Rampart", "Sable", "Tidewater", "Union", "Vestry",
            "Waypoint", "Yardley", "Ashbury", "Brightwater", "Copperfield", "Drayton"
    );

    public static final List<String> CLIENT_TYPES = List.of(
            "Restaurant Group", "Dental Partners", "Property Management", "Auto Group",
            "Medical Associates", "Veterinary Clinic", "Construction", "Farms", "Brewing",
            "Retail Group", "Fitness", "Salon Group", "Bakery", "Trucking", "Nursery",
            "Orthodontics", "Physical Therapy", "Law Group", "Realty", "Distributors"
    );

    /**
     * Memo text. Never read by any rule &mdash; purely so the exceptions page gives a human
     * something to recognise when they are deciding what a payment actually was.
     */
    public static final List<String> MEMO_TEMPLATES = List.of(
            "Invoice %d", "Inv #%d", "Job %d - final", "Job %d - progress billing",
            "Monthly service %d", "PO %d", "Work order %d", "Contract %d milestone",
            "Repair ticket %d", "Site visit %d", "Retainer %d", "Deposit on job %d",
            "Materials and labor, inv %d", "Emergency callout %d", "Quarterly maintenance %d"
    );

    /** Reason text for reversals, so the explanation reads like a real ledger. */
    public static final List<String> REVERSAL_MEMOS = List.of(
            "Void - duplicate payment", "Refund - overbilled", "Reversal - wrong vendor",
            "Credit memo applied", "Returned - work not completed", "Check voided, reissued",
            "Refund - cancelled job", "Correction to prior payment"
    );
}
