package reseptish;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import reseptish.db.Database;
import reseptish.db.KategoriaDao;
import reseptish.db.PostgresDatabase;
import reseptish.db.RaakaaineDao;
import reseptish.db.ReseptiDao;
import reseptish.db.ReseptiKategoriaDao;
import reseptish.db.ReseptiRaakaaineDao;
import reseptish.db.SQLiteDatabase;
import reseptish.pojo.Kategoria;
import reseptish.pojo.Raakaaine;
import reseptish.pojo.Resepti;
import reseptish.pojo.ReseptiKategoria;
import reseptish.pojo.ReseptiRaakaaine;
import spark.ModelAndView;
import spark.Request;
import spark.Spark;
import spark.template.thymeleaf.ThymeleafTemplateEngine;

public class Main {

    public static void main(String[] args) throws Exception {
        // asetetaan portti jos heroku antaa PORT-ympäristömuuttujan
        if (System.getenv("PORT") != null) {
            Spark.port(Integer.valueOf(System.getenv("PORT")));
        }

        Database db = new PostgresDatabase(System.getenv("JDBC_DATABASE_URL"));
        db.init();

        ReseptiRaakaaineDao reseptiRaakaaineDao = new ReseptiRaakaaineDao(db);
        ReseptiDao reseptiDao = new ReseptiDao(db);
        RaakaaineDao raakaaineDao = new RaakaaineDao(db);
        KategoriaDao kategoriaDao = new KategoriaDao(db);
        ReseptiKategoriaDao reseptiKategoriaDao = new ReseptiKategoriaDao(db);

        //Etusivu
        Spark.get("/", (req, res) -> {
            return new ModelAndView(Collections.emptyMap(), "index");
        }, new ThymeleafTemplateEngine());

        //Yksittäinen resepti
        Spark.get("/resepti/:id", (req, res) -> {
            Map map = new HashMap<>();
            List<ReseptiKategoria> reseptiKategoriat = reseptiKategoriaDao.findAllForResepti(Integer.parseInt(req.params("id")));
            List<ReseptiRaakaaine> reseptiJaRaakaaineet = reseptiRaakaaineDao.findAllForResepti(Integer.parseInt(req.params("id")));
            Resepti resepti = reseptiJaRaakaaineet.get(0).getResepti();

            map.put("resepti", resepti);
            map.put("raakaaineet", reseptiJaRaakaaineet);
            map.put("kategoriat", reseptiKategoriat);

            return new ModelAndView(map, "soloresepti");
        }, new ThymeleafTemplateEngine());

        //Kaikki reseptit
        Spark.get("/reseptit", (req, res) -> {
            HashMap map = new HashMap<>();

            map.put("reseptit", reseptiDao.findAll());

            return new ModelAndView(map, "reseptit");
        }, new ThymeleafTemplateEngine());

        //Haku
        Spark.get("/haku", (req, res) -> {
            return new ModelAndView(Collections.emptyMap(), "haku");
        }, new ThymeleafTemplateEngine());
        Spark.post("/haku", (req, res) -> {
            Map<String, String> params = parseBody(req);
            
            Map map = new HashMap<>();
            
            String hakutyyppi = params.get("hakutyyppi");
            if ("resepti".equals(hakutyyppi)) {
                map.put("reseptit", reseptiDao.find(params.get("resepti")));
            } else if ("raakaAine".equals(hakutyyppi)) {
                map.put("reseptit", reseptiRaakaaineDao.findReseptiWithRaakaaaine(params.get("resepti")));
            }
            
            return new ModelAndView(map, "haku");
        }, new ThymeleafTemplateEngine());
        
        
        //Uusi
        Spark.get("/uusi", (req, res) -> {
            return new ModelAndView(Collections.emptyMap(), "uusi");
        }, new ThymeleafTemplateEngine());

        Spark.post("/uusi", (req, res) -> {
            Map<String, String> params = parseBody(req);
            
            if (params.get("ohje") == null || params.get("ohje").isEmpty()
                    || params.get("tekija") == null || params.get("tekija").isEmpty()
                    || params.get("nimi") == null || params.get("nimi").isEmpty()
                    || params.get("valmistusaika") == null || params.get("valmistusaika").isEmpty()
                    || params.get("raaka-aine1") == null || params.get("raaka-aine1").isEmpty()
                    || params.get("maara1") == null || params.get("maara1").isEmpty()
                    || params.get("yksikko1") == null || params.get("yksikko1").isEmpty()) {
                res.redirect("/error");
                return "";
            }
            
            Resepti resepti = new Resepti(null, params.get("nimi"), params.get("ohje"),
                    params.get("tekija"), Integer.parseInt(params.get("valmistusaika")));
            
            //  palauttaa reseptin Id:n (ainakin teoriassa)
            resepti.setReseptiId(reseptiDao.add(resepti));
            
            //Kategorioiden lisääminen
            String kategoriat = params.get("kategoriat");
            if (kategoriat != null && !kategoriat.isEmpty()) {
                for (String kategoria : kategoriat.split(",")) {
                    kategoriaDao.add(kategoria);

                    reseptiKategoriaDao.add(new ReseptiKategoria(resepti, kategoriaDao.search(kategoria)));
                }
            }

            for (int i = 1; i < 15; i++) {
                String raakaaine = params.get("raaka-aine" + i);
                String maara = params.get("maara" + i);
                String yksikko = params.get("yksikko" + i);
                String raakaaineOhje = params.get("raakaaineOhje"+i);

                if (raakaaine == null || raakaaine.isEmpty() || maara == null || maara.isEmpty() || yksikko == null || yksikko.isEmpty()) {
                    continue;
                }
                
                raakaaineDao.add(raakaaine);

                ReseptiRaakaaine uusi = new ReseptiRaakaaine(resepti,
                        raakaaineDao.search(raakaaine), Integer.parseInt(maara), yksikko, i, raakaaineOhje);

                reseptiRaakaaineDao.add(uusi);
            }

            res.redirect("/resepti/" + resepti.getReseptiId());
            return "";
        });

        //Tilastot
        Spark.get("/tilasto", (req, res) -> {
            Map map = new HashMap<>();

            map.put("maara", reseptiDao.count());
            
            //15 suosituinta kategoriaa
            
            Map<String, Integer> kategoriaMap = reseptiKategoriaDao.kategoriaCount();
            List<String> kategoriat = new ArrayList<>();
            kategoriaMap.entrySet().stream().forEach(e -> {
                kategoriat.add(e.getValue()+" - "+ e.getKey());
            });
            //System.out.println(kategoriat);
            
            Collections.sort(kategoriat, Collections.reverseOrder());
            
            ArrayList<String> jarjkategoriat =kategoriat.stream().limit(15).collect(Collectors.toCollection(ArrayList::new));
            
             map.put("kategoriat", jarjkategoriat);


            //15 suosituinta raaka-ainetta
            
            Map<String, Integer> raakaaineMap = reseptiRaakaaineDao.raakaAineCount();
            List<String> raakaaineet = new ArrayList<>();
            raakaaineMap.entrySet().stream().forEach(e -> {
                raakaaineet.add(e.getValue()+" - "+ e.getKey());
            });
            
            Collections.sort(raakaaineet, Collections.reverseOrder());
            ArrayList<String> jarjraakaaineet =raakaaineet.stream().limit(15).collect(Collectors.toCollection(ArrayList::new));
            map.put("raakaaineet", jarjraakaaineet);
            


           
            return new ModelAndView(map, "tilasto");
        }, new ThymeleafTemplateEngine());

        //Virhe
        Spark.get("/error", (req, res) -> {
            return "Reseptin lisääminen puutteellisin tiedoin ei onnistu";
        });

        /*Spark.get("/opiskelijat/:id", (req, res) -> {
            HashMap map = new HashMap<>();
//            map.put("opiskelija", opiskelijaDao.findOne(Integer.parseInt(req.params("id"))));

            return new ModelAndView(map, "opiskelija");
        }, new ThymeleafTemplateEngine());*/
    }
    
    /**
     * Spark ei anna parametrejä oikein
     **/
    private static Map<String, String> parseBody(Request req) throws UnsupportedEncodingException {
        //POST bodyn parsiminen
        Map<String, String> params = new HashMap<>();
        System.out.println(req.body());
        for (String param : req.body().split("&")) {
            String[] split = param.split("=");
            System.out.println(Arrays.toString(split));
            params.put(split[0], split.length > 1 ? URLDecoder.decode(split[1], "UTF-8") : null);
        }
        return params;
    }
}
