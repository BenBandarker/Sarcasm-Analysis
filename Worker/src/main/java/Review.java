public class Review{

    enum Color{
        DARK_RED(0),
        RED(1),
        BLACK(2),
        LIGHT_GREEN(3),
        DARK_GREEN(4);

        int id;

        Color(int id){this.id = id;}
        public int getId(){return id;}

        public static Color getColorById(int id) {
            for (Color color : Color.values()) {
                if (color.getId() == id) {return color;}
            }
            throw new IllegalArgumentException("No Color Found for ID: " + id);
        }
    };

    enum State{
        IS_SARCASTIC,
        NOT_SARCASTIC,
        NOT_CHECKED
    }; 

    //---------------------------Field---------------------------//
    //SarcasmState defines if the review is sarcastic or not.
    private State sarcasmState = State.NOT_CHECKED;

    private String link;

    //Entities define all the entities found in the review.
    private String entities;


    //------------------------Constructor------------------------//

    public Review(){
        return;
    }

    public Review(String link,int oldRating, int newRating, String entities){
        //step 1: extract all needed data from the review.
        this.link = link;
        this.entities = entities;
        
        colouredLink(Color.getColorById(newRating));
        if(oldRating == newRating)
            this.sarcasmState = State.NOT_SARCASTIC;

        else 
            this.sarcasmState = State.IS_SARCASTIC;
    }

    //--------------------------Methods--------------------------//

    public String getLink(){
        return this.link;
    }


    public String getEntities(){
        return this.entities;
    }

    //ColouredLink gets a color and change the color of the review link field.
    public void colouredLink(Color color){
        String colouredLink = "";
        String start = "<a href=\"" + this.link +"\" style=\"color: ";
        String end = ";\">"+ this.link + "</a>";
        
        switch(color){
            case DARK_RED:
                colouredLink = start + "darkred" + end;
                break;
            case RED:
                colouredLink = start + "red" + end;
                break;
            case BLACK:
                colouredLink = start + "black" + end;
                break;
            case LIGHT_GREEN:
                colouredLink = start + "lightgreen" + end;
                break;
            case DARK_GREEN:
                colouredLink = start + "darkgreen" + end;
                break;
        }
        this.link = colouredLink;
    }

    //ToString return a string that represents the review.
    public String toString(){
        return "{Link: " + this.link + " ,Entities: " + this.entities + " ,Sarcasm: " + this.sarcasmState +"}";
    }
}